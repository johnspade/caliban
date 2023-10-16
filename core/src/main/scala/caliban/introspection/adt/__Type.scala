package caliban.introspection.adt

import caliban.Value.StringValue
import caliban.parsing.adt.Definition.TypeSystemDefinition.TypeDefinition
import caliban.parsing.adt.Definition.TypeSystemDefinition.TypeDefinition._
import caliban.parsing.adt.Type.{ ListType, NamedType }
import caliban.parsing.adt.{ Directive, Type }
import caliban.schema.Annotations.GQLExcluded
import caliban.schema.Types

case class __Type(
  kind: __TypeKind,
  name: Option[String] = None,
  description: Option[String] = None,
  fields: __DeprecatedArgs => Option[List[__Field]] = _ => None,
  interfaces: () => Option[List[__Type]] = () => None,
  possibleTypes: Option[List[__Type]] = None,
  enumValues: __DeprecatedArgs => Option[List[__EnumValue]] = _ => None,
  inputFields: __DeprecatedArgs => Option[List[__InputValue]] = _ => None,
  ofType: Option[__Type] = None,
  specifiedBy: Option[String] = None,
  @GQLExcluded directives: Option[List[Directive]] = None,
  @GQLExcluded origin: Option[String] = None
) { self =>
  def |+|(that: __Type): __Type = __Type(
    kind,
    (name ++ that.name).reduceOption((_, b) => b),
    (description ++ that.description).reduceOption((_, b) => b),
    args => (fields(args) ++ that.fields(args)).reduceOption(_ ++ _),
    () => (interfaces() ++ that.interfaces()).reduceOption(_ ++ _),
    (possibleTypes ++ that.possibleTypes).reduceOption(_ ++ _),
    args => (enumValues(args) ++ that.enumValues(args)).reduceOption(_ ++ _),
    args => (inputFields(args) ++ that.inputFields(args)).reduceOption(_ ++ _),
    (ofType ++ that.ofType).reduceOption(_ |+| _),
    (specifiedBy ++ that.specifiedBy).reduceOption((_, b) => b),
    (directives ++ that.directives).reduceOption(_ ++ _),
    (origin ++ that.origin).reduceOption((_, b) => b)
  )

  def toType(nonNull: Boolean = false): Type =
    ofType match {
      case Some(of) =>
        kind match {
          case __TypeKind.LIST     => ListType(of.toType(), nonNull)
          case __TypeKind.NON_NULL => of.toType(true)
          case _                   => NamedType(name.getOrElse(""), nonNull)
        }
      case None     => NamedType(name.getOrElse(""), nonNull)
    }

  def toTypeDefinition: Option[TypeDefinition] =
    kind match {
      case __TypeKind.SCALAR       =>
        Some(
          ScalarTypeDefinition(
            description,
            name.getOrElse(""),
            directives
              .getOrElse(Nil) ++
              specifiedBy
                .map(url => Directive("specifiedBy", Map("url" -> StringValue(url)), directives.size))
                .toList
          )
        )
      case __TypeKind.OBJECT       =>
        Some(
          ObjectTypeDefinition(
            description,
            name.getOrElse(""),
            interfaces().getOrElse(Nil).map(t => NamedType(t.name.getOrElse(""), nonNull = false)),
            directives.getOrElse(Nil),
            allFields.map(_.toFieldDefinition)
          )
        )
      case __TypeKind.INTERFACE    =>
        Some(
          InterfaceTypeDefinition(
            description,
            name.getOrElse(""),
            interfaces().getOrElse(Nil).map(t => NamedType(t.name.getOrElse(""), nonNull = false)),
            directives.getOrElse(Nil),
            allFields.map(_.toFieldDefinition)
          )
        )
      case __TypeKind.UNION        =>
        Some(
          UnionTypeDefinition(
            description,
            name.getOrElse(""),
            directives.getOrElse(Nil),
            possibleTypes.getOrElse(Nil).flatMap(_.name)
          )
        )
      case __TypeKind.ENUM         =>
        Some(
          EnumTypeDefinition(
            description,
            name.getOrElse(""),
            directives.getOrElse(Nil),
            enumValues(__DeprecatedArgs(Some(true))).getOrElse(Nil).map(_.toEnumValueDefinition)
          )
        )
      case __TypeKind.INPUT_OBJECT =>
        Some(
          InputObjectTypeDefinition(
            description,
            name.getOrElse(""),
            directives.getOrElse(Nil),
            allInputFields.map(_.toInputValueDefinition)
          )
        )
      case _                       => None
    }

  def isNullable: Boolean =
    kind match {
      case __TypeKind.NON_NULL => false
      case _                   => true
    }

  lazy val list: __Type    = __Type(__TypeKind.LIST, ofType = Some(self))
  lazy val nonNull: __Type = __Type(__TypeKind.NON_NULL, ofType = Some(self))

  lazy val allFields: List[__Field] =
    fields(__DeprecatedArgs(Some(true))).getOrElse(Nil)

  lazy val allInputFields: List[__InputValue] =
    inputFields(__DeprecatedArgs(Some(true))).getOrElse(Nil)

  lazy val allEnumValues: List[__EnumValue] =
    enumValues(__DeprecatedArgs(Some(true))).getOrElse(Nil)

  private[caliban] lazy val allFieldsMap: Map[String, __Field] =
    allFields.map(f => f.name -> f).toMap

  lazy val innerType: __Type = Types.innerType(this)
}

sealed trait TypeVisitor { self =>
  import TypeVisitor._

  def |+|(that: TypeVisitor): TypeVisitor = TypeVisitor.Combine(self, that)

  def visit(t: __Type): __Type = {
    def collect(visitor: TypeVisitor): __Type => __Type =
      visitor match {
        case Empty           => identity
        case Modify(f)       => f
        case Combine(v1, v2) => collect(v1) andThen collect(v2)
      }

    val f = collect(self)

    def loop(t: __Type): __Type =
      f(
        t.copy(
          fields = args => t.fields(args).map(_.map(field => field.copy(`type` = () => loop(field.`type`())))),
          inputFields =
            args => t.inputFields(args).map(_.map(field => field.copy(`type` = () => loop(field.`type`())))),
          interfaces = () => t.interfaces().map(_.map(loop)),
          possibleTypes = t.possibleTypes.map(_.map(loop)),
          ofType = t.ofType.map(loop)
        )
      )

    loop(t)
  }
}

object TypeVisitor {
  private case object Empty                                    extends TypeVisitor {
    override def |+|(that: TypeVisitor): TypeVisitor = that
    override def visit(t: __Type): __Type            = t
  }
  private case class Modify(f: __Type => __Type)               extends TypeVisitor
  private case class Combine(v1: TypeVisitor, v2: TypeVisitor) extends TypeVisitor

  val empty: TypeVisitor                                               = Empty
  def modify(f: __Type => __Type): TypeVisitor                         = Modify(f)
  private[caliban] def modify[A](visitor: ListVisitor[A]): TypeVisitor = modify(t => visitor.visit(t))

  object fields      extends ListVisitorConstructors[__Field]      {
    val set: __Type => (List[__Field] => List[__Field]) => __Type =
      t => f => t.copy(fields = args => t.fields(args).map(f))
  }
  object inputFields extends ListVisitorConstructors[__InputValue] {
    val set: __Type => (List[__InputValue] => List[__InputValue]) => __Type =
      t => f => t.copy(inputFields = args => t.inputFields(args).map(f))
  }
  object enumValues  extends ListVisitorConstructors[__EnumValue]  {
    val set: __Type => (List[__EnumValue] => List[__EnumValue]) => __Type =
      t => f => t.copy(enumValues = args => t.enumValues(args).map(f))
  }
  object directives  extends ListVisitorConstructors[Directive]    {
    val set: __Type => (List[Directive] => List[Directive]) => __Type =
      t => f => t.copy(directives = t.directives.map(f))
  }

  def renameType(f: PartialFunction[String, String]): TypeVisitor = {
    def rename(name: String): String = f.lift(name).getOrElse(name)

    TypeVisitor.modify(t => t.copy(name = t.name.map(rename))) |+|
      TypeVisitor.enumValues.modify(v => v.copy(name = rename(v.name)))
  }

  def renameField(f: PartialFunction[(String, String), String]): TypeVisitor =
    TypeVisitor.fields.modifyWith((t, field) =>
      f.lift(t.name.getOrElse("") -> field.name)
        .fold(field)(newName =>
          field.copy(
            name = newName,
            renameInput = field.renameInput andThen ((name: String) => if (name == newName) field.name else name)
          )
        )
    ) |+|
      TypeVisitor.inputFields.modifyWith((t, field) =>
        f.lift(t.name.getOrElse("") -> field.name)
          .fold(field)(newName =>
            field.copy(
              name = newName,
              renameInput = field.renameInput andThen ((name: String) => if (name == newName) field.name else name)
            )
          )
      )

  def renameArgument(
    f: PartialFunction[(String, String), (String, String)]
  ): TypeVisitor =
    TypeVisitor.fields.modifyWith((t, field) =>
      f.lift((t.name.getOrElse(""), field.name)) match {
        case Some((oldName, newName)) =>
          field.copy(args =
            args =>
              field
                .args(args)
                .map(arg =>
                  if (arg.name == oldName)
                    arg.copy(
                      name = newName,
                      renameInput = arg.renameInput andThen ((name: String) => if (name == newName) oldName else name)
                    )
                  else arg
                )
          )
        case None                     => field
      }
    )

  def filterField(f: PartialFunction[(String, String), Boolean]): TypeVisitor =
    TypeVisitor.fields.filterWith((t, field) => f.lift((t.name.getOrElse(""), field.name)).getOrElse(true)) |+|
      TypeVisitor.inputFields.filterWith((t, field) => f.lift((t.name.getOrElse(""), field.name)).getOrElse(true))

  def filterInterface(f: PartialFunction[(String, String), Boolean]): TypeVisitor =
    TypeVisitor.modify(t =>
      t.copy(interfaces =
        () =>
          t.interfaces()
            .map(_.filter(interface => f.lift((t.name.getOrElse(""), interface.name.getOrElse(""))).getOrElse(true)))
      )
    )

  def filterArgument(f: PartialFunction[(String, String, String), Boolean]): TypeVisitor =
    TypeVisitor.fields.modifyWith((t, field) =>
      field.copy(args =
        args => field.args(args).filter(arg => f.lift((t.name.getOrElse(""), field.name, arg.name)).getOrElse(true))
      )
    )

}

private[caliban] sealed abstract class ListVisitor[A](implicit val set: __Type => (List[A] => List[A]) => __Type) {
  self =>
  import ListVisitor._

  def visit(t: __Type): __Type =
    self match {
      case Filter(predicate) => set(t)(_.filter(predicate(t)))
      case Modify(f)         => set(t)(_.map(f(t)))
      case Add(f)            => set(t)(f(t).foldLeft(_) { case (as, a) => a :: as })
    }
}

private[caliban] object ListVisitor {
  private case class Filter[A](predicate: __Type => A => Boolean)(implicit
    set: __Type => (List[A] => List[A]) => __Type
  ) extends ListVisitor[A]
  private case class Modify[A](f: __Type => A => A)(implicit
    set: __Type => (List[A] => List[A]) => __Type
  ) extends ListVisitor[A]
  private case class Add[A](f: __Type => List[A])(implicit
    set: __Type => (List[A] => List[A]) => __Type
  ) extends ListVisitor[A]

  def filter[A](predicate: (__Type, A) => Boolean)(implicit
    set: __Type => (List[A] => List[A]) => __Type
  ): TypeVisitor =
    TypeVisitor.modify(Filter[A](t => field => predicate(t, field)))
  def modify[A](f: (__Type, A) => A)(implicit set: __Type => (List[A] => List[A]) => __Type): TypeVisitor =
    TypeVisitor.modify(Modify[A](t => field => f(t, field)))
  def add[A](f: __Type => List[A])(implicit set: __Type => (List[A] => List[A]) => __Type): TypeVisitor   =
    TypeVisitor.modify(Add(f))
}

private[caliban] trait ListVisitorConstructors[A] {
  implicit val set: __Type => (List[A] => List[A]) => __Type

  def filter(predicate: A => Boolean): TypeVisitor               = filterWith((_, a) => predicate(a))
  def filterWith(predicate: (__Type, A) => Boolean): TypeVisitor = ListVisitor.filter(predicate)
  def modify(f: A => A): TypeVisitor                             = modifyWith((_, a) => f(a))
  def modifyWith(f: (__Type, A) => A): TypeVisitor               = ListVisitor.modify(f)
  def add(list: List[A]): TypeVisitor                            = addWith(_ => list)
  def addWith(f: __Type => List[A]): TypeVisitor                 = ListVisitor.add(f)
}
