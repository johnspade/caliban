package caliban.gateway

import caliban.CalibanError.ExecutionError
import caliban.ResponseValue.{ ListValue, ObjectValue }
import caliban.Value.NullValue
import caliban.execution._
import caliban.gateway.FetchDataSource.FetchRequest
import caliban.gateway.SubGraph.SubGraphExecutor
import caliban.introspection.adt.{ __Directive, Extend, TypeVisitor }
import caliban.schema.Step.NullStep
import caliban.schema.{ Operation, RootSchemaBuilder, Types }
import caliban.wrappers.Wrapper
import caliban.wrappers.Wrapper.FieldWrapper
import caliban.{ CalibanError, GraphQL, GraphQLResponse, ResponseValue }
import zio.prelude.NonEmptyList
import zio.query.ZQuery
import zio.{ Chunk, URIO }

private case class SuperGraphExecutor[-R](
  private val subGraphs: NonEmptyList[SubGraphExecutor[R]],
  private val transformers: Chunk[TypeVisitor]
) extends GraphQL[R] {
  private val subGraphMap: Map[String, SubGraphExecutor[R]] = subGraphs.map(g => g.name -> g).toMap

  protected val wrappers: List[Wrapper[R]]              = Nil
  protected val additionalDirectives: List[__Directive] = Nil
  protected val features: Set[Feature]                  = Set.empty
  protected val schemaBuilder: RootSchemaBuilder[R]     = {
    val builder = subGraphs.collect {
      case subGraph if subGraph.exposeAtRoot =>
        val rootTypes = Set(
          subGraph.schema.queryType.name,
          subGraph.schema.mutationType.flatMap(_.name),
          subGraph.schema.subscriptionType.flatMap(_.name)
        ).flatten
        RootSchemaBuilder(
          Some(Operation(subGraph.schema.queryType, NullStep)),
          subGraph.schema.mutationType.map(mutation => Operation(mutation, NullStep)),
          subGraph.schema.subscriptionType.map(subscription => Operation(subscription, NullStep))
        ).visit(
          TypeVisitor.fields.modifyWith((t, field) =>
            if (t.name.exists(rootTypes.contains)) field.copy(extend = Some(Extend(subGraph.name, field.name)))
            else field
          )
        )
    }.reduceLeft(_ |+| _)
    transformers.foldLeft(builder) { case (builder, transformer) => builder.visit(transformer) }
  }

  protected override def resolve[R1 <: R](
    op: Operation[R1],
    fieldWrappers: List[FieldWrapper[R1]],
    isIntrospection: Boolean
  )(req: ExecutionRequest): URIO[R1, GraphQLResponse[CalibanError]] =
    if (isIntrospection)
      Executor.executeRequest(req, op.plan, fieldWrappers, QueryExecution.Parallel, features)
    else
      resolveField(Resolver.Field(req.field), NullValue)
        .fold(
          error => GraphQLResponse(NullValue, List(error)),
          result => GraphQLResponse(result, Nil)
        )
        .run

  private def resolveField(field: Resolver.Field, parent: ResponseValue): ZQuery[R, ExecutionError, ResponseValue] =
    field.resolver match {
      case Resolver.Extract(extract, children)                                                     =>
        extract(parent.asObjectValue.getOrElse(ObjectValue(Nil))) match {
          case res @ ObjectValue(fields) =>
            ZQuery
              .foreachBatched(children)(child => resolveField(child, res).map(child -> _))
              .map(children =>
                res.copy(fields = fields ++ children.map { case (field, response) =>
                  field.outputName -> response
                })
              )
          case other                     => ZQuery.succeed(other)
        }
      case Resolver.Fetch(subGraph, sourceFieldName, fields, argumentMappings, filterBatchResults) =>
        subGraphMap.get(subGraph) match {
          case Some(subGraph) =>
            lazy val parentObject = parent.asObjectValue.getOrElse(ObjectValue(Nil))
            val arguments         = field.arguments ++ argumentMappings.map { case (k, f) =>
              f(parentObject.get(k).toInputValue)
            }.filterNot { case (_, v) => v == NullValue }
            ZQuery
              .fromRequest(
                FetchRequest(
                  subGraph,
                  sourceFieldName,
                  fields
                    .flatMap(f =>
                      f.resolver match {
                        case _: Resolver.Extract                          => Set(f.name)
                        case Resolver.Fetch(_, _, _, argumentMappings, _) => argumentMappings.keySet
                      }
                    )
                    .distinct
                    .map(name => Field(name, Types.string, None)),
                  arguments,
                  filterBatchResults.isDefined
                )
              )(FetchDataSource[R]) // TODO don't make new datasource for each request
              .map(_.asObjectValue.map(_.get(sourceFieldName)).getOrElse(NullValue))
              .map(value =>
                value.asListValue
                  .fold(value)(values =>
                    filterBatchResults.fold(value)(map =>
                      values.filter(_.asObjectValue.fold(true)(v => map(parentObject, v)))
                    )
                  )
              )
              .flatMap {
                case ListValue(values) =>
                  ZQuery
                    .foreachBatched(values)(value =>
                      ZQuery
                        .foreachBatched(fields)(field => resolveField(field, value).map(field.outputName -> _))
                        .map(ObjectValue.apply)
                    )
                    .map(ListValue.apply)
                case value             =>
                  ZQuery
                    .foreachBatched(fields)(field => resolveField(field, value).map(field.outputName -> _))
                    .map(ObjectValue.apply)
              }
          case None           => ZQuery.fail(ExecutionError(s"Subgraph $subGraph not found"))
        }
    }
}
