package provingground

import HoTT._
import ScalaRep._
//import scala.language.{existentials, implicitConversions}
import scala.util._
import spire.algebra._
import spire.implicits._
//import spire.syntax._

/**
  * @author gadgil
  *
  * Symbolic algebra for numeric types, with Sigma's and Pi's
  * More generally start with a spire CRig
  * Requires a commutative rig, but Pi's and Sigma's are written to allow rings and fields
  *
  * Terms of type RepTerm[A] are created, i.e., terms with the refinement that they correspond to the scala type A.
  *
  * Any term should be of the following forms:
  *
  * With respect to addition, if p denoted non-literal terms indecomposable with respect to addition:
  *
  * * literal c
  * * p
  * * c + p
  * * c + Sigma{ps}, ps a set of elements p with at least two elements.
  *
  * With respect to multiplication, if x denotes an element indecomposable under both addition and multiplication, c is a literal, and k is an integer.
  * * x
  * * c x
  * * c Pi{(x -> k)}; no k is zero, there are either at least two terms or there is one term with k not 1.
  *
  * Using type Rig, not CRig as CRing does not extend CRig
  */
class SymbolicCRig[A : Rig] { self =>
  val rig = implicitly[Rig[A]]

  import rig.{sumn, zero, one}

  val two = rig.plus(rig.one, rig.one)

  type LocalTerm = RepTerm[A]

  type Op = Func[LocalTerm, Func[LocalTerm, LocalTerm]]

  object LocalTyp extends ScalaTyp[A] {
    override def toString = self.toString() + ".Typ"

    override type Obj = LocalTerm
  }

  object Literal extends ScalaSym[LocalTerm, A](LocalTyp) {
    def fromInt(n: Int) = Literal(sumn(one, n))
  }

  object Comb {
    def unapply(term: Term): Option[(Op, LocalTerm, LocalTerm)] = term match {
      case FormalAppln(FormalAppln(op, x), y) =>
        Try(
            (op.asInstanceOf[Op],
             x.asInstanceOf[LocalTerm],
             y.asInstanceOf[LocalTerm])).toOption
      case _ => None
    }

    def apply(op: Func[LocalTerm, Func[LocalTerm, LocalTerm]],
              x: LocalTerm,
              y: LocalTerm) =
      FormalAppln(FormalAppln(op, x), y)
  }

  case class SigmaTerm(elems: Set[LocalTerm])
      extends LocalTerm with FoldedTerm[LocalTerm] {
    require(
        elems.size > 1,
        s"Cannot create Sigma term of set $elems with less than 2 elements")

//    println(s"created sigma-term [$elems]")

    val op = sum

    val typ = LocalTyp

    def subs(x: Term, y: Term) =
      (elems.toList map (_.replace(x, y)))
        .reduceRight((a: LocalTerm, b: LocalTerm) => sum(a)(b))

    def newobj = LocalTyp.obj

    val head = elems.head

    val tail =
      if (elems.tail.size == 1) elems.tail.head else SigmaTerm(elems.tail)

    /**
      * add a term,
      * simplifies assuming the term added is in normal form, and does not involve a literal
      */
    def +:(x: LocalTerm): LocalTerm = {
      val l = SigmaTerm.fold(x)(elems.toList)
      l match {
        case List() => Literal(zero)
        case s :: List() => s
        case _ => SigmaTerm(l.toSet)
      }
    }
  }

  object SigmaTerm {
    import LitProd.addReduce

    /**
      * recursively fold in a term to a list of terms.
      * if sum with head simplifies, this is called recursively,
      * otherwise head is retained and term is added with fold to tail.
      * this assumes that we cannot have chains of simplifications, as list is already simplified.
      */
    def fold(x: LocalTerm)(l: List[LocalTerm]): List[LocalTerm] =
      (x, l) match {
        case (_, List()) => List(x)
        case (_, head :: tail) =>
          (addReduce(x, head) map ((u: LocalTerm) => fold(u)(tail)))
            .getOrElse(head :: fold(x)(tail))
      }
  }

  /**
    * matching, building for formal product with a literal
    */
  object LitProd {
    def apply(a: A, x: LocalTerm) = prod(Literal(a))(x)

    def unapply(x: LocalTerm): Option[(A, LocalTerm)] = x match {
      case Comb(mult, Literal(a), y) if mult == prod =>
        Some((a, y))
      case _ => None
    }

    /**
      * (optionally) returns a single term (w.r.t. +) after summing if simplification takes place.
      */
    def addReduce(x: LocalTerm, y: LocalTerm) = (x, y) match {
      case (LitProd(a, u), LitProd(b, v)) if u == v =>
        Some(LitProd(a + b, u))
      case (LitProd(a, u), v) if u == v =>
        Some(LitProd(rig.plus(a, rig.one), u))
      case (v, LitProd(a, u)) if u == v =>
        Some(LitProd(rig.plus(a, rig.one), u))
      case (u, v) if u == v =>
        Some(LitProd(two, u))
      case _ => None
    }
  }

  /**
    * A product of terms in normal form, i.e.,
    * * none of the terms is a sum
    * * we have either at least two terms or a single term with exponent not 1,
    * * no exponent is 0.
    */
  case class PiTerm(multElems: Map[LocalTerm, Int])
      extends LocalTerm with FoldedTerm[LocalTerm] {
    val typ = LocalTyp

    def subs(x: Term, y: Term) =
      (atomize map (_.replace(x, y)))
        .reduceRight((a: LocalTerm, b: LocalTerm) => prod(a)(b))

    def newobj = LocalTyp.obj

    lazy val atomize: List[LocalTerm] = {
      multElems.toList flatMap {
        case (x, e) =>
          if (e > 0) List.fill(e)(x) else List.fill(-e)(PiTerm(Map(x -> -1)))
      }
    }

    lazy val head = multElems.head match {
      case (x, k) if k == -1 => PiTerm(Map(x -> k))
      case (x, k) if k == 1 => x
      case (x, k) if k > 1 => x
      case (x, k) if k < -1 => PiTerm(Map(x -> (-1)))
    }

    lazy val tail = multElems.head match {
      case (x, k) if math.abs(k) == 1 => PiTerm.reduce(multElems.tail)
      case (x, k) if k > 1 => PiTerm.reduce(multElems.tail + (x -> (k - 1)))
      case (x, k) if k < -1 => PiTerm.reduce(multElems.tail + (x -> (k + 1)))
    }

    val isComposite =
      (multElems.size > 1) || (math.abs(multElems.head._2) != 1)

    def *:(y: LocalTerm) = {
      import Reciprocal.{base, expo}

      val ind = (multElems.get(base(y)) map (_ + expo(y))) getOrElse (expo(y))
      PiTerm.purge(multElems + (base(y) -> ind))
    }

    val elems =
      multElems.keys.toList flatMap ((x) => PiTerm.powList(x, multElems(x)))

    val op = prod
  }

  object PiTerm {
    def purge(elems: Map[LocalTerm, Int]) = {
      val nontriv = elems filter ({ case (x, p) => p != 0 })
      PiTerm.reduce(nontriv)
    }

    def reduce(elems: Map[LocalTerm, Int]) = {
      val keys = elems.keySet
      if (keys.isEmpty) Literal(one)
      else if (keys.size > 1 || elems(keys.head) != 1) PiTerm(elems)
      else keys.head
    }

    def powList(x: LocalTerm, k: Int) = {
      val base = if (k < 0) Reciprocal.Formal(x) else x
      List.fill(math.abs(k))(base)
    }
  }

  /**
    * override this in fields
    */
  val reciprocalOpt: Option[Func[LocalTerm, LocalTerm]] = None

  lazy val reciprocal: Func[LocalTerm, LocalTerm] = reciprocalOpt.get

  object Reciprocal {
    def apply(a: LocalTerm) = a match {
      case Reciprocal(b) => b
      case _ => PiTerm(Map(a -> -1))
    }

    def unapply(a: Term) = a match {
      case PiTerm(elems) => {
          elems.toList match {
            case List(xp) if xp._2 == -1 => Some(xp._1)
            case _ => None
          }
        }
      case FormalAppln(r, b: LocalTerm) if Some(r) == reciprocalOpt => Some(b)
      case _ => None
    }

    def base(y: LocalTerm) = y match {
      case Reciprocal(a) => a
      case a => a
    }

    def expo(y: LocalTerm) = y match {
      case Reciprocal(a) => -1
      case _ => 1
    }

    /*
  case class Formal(x: LocalTerm) extends LocalTerm with FoldedTerm[LocalTerm]{

    def newobj  = this
    def subs(u: provingground.HoTT.Term,y: provingground.HoTT.Term)  = Formal(x.subs(u, y))

    val typ = LocalTyp

    val op = div
    val elems : List[LocalTerm] = List(Literal(one), x)
  }*/

    object Formal {
      def apply(x: LocalTerm) = FormalAppln(reciprocal, x)
    }
  }

  import LocalTyp.rep

  case object sum extends Func[LocalTerm, Func[LocalTerm, LocalTerm]] {
    val dom = LocalTyp

    val codom = LocalTyp ->: LocalTyp

    val typ = dom ->: codom

    def subs(x: Term, y: Term) = this

    def newobj = this

    def act(x: LocalTerm) = x match {
      case Literal(a) =>
        if (a == zero) {
          val x = LocalTyp.Var
          lmbda(x)(x)
        } else AddLiteral(a)
      case Comb(op, u, v) if op == sum =>
        composition(sum(u), sum(v))
      case s @ SigmaTerm(terms) =>
        composition(sum(s.head), sum(s.tail))
      case y => AddTerm(y)
    }

    override def toString = "sum"
  }

  object LiteralSum {
    def unapply(x: LocalTerm) = x match {
      case Comb(f, Literal(b), v) if f == sum => Some((b, v))
      case _ => None
    }
  }

  case class AddLiteral(a: A) extends Func[LocalTerm, LocalTerm] {
    val dom = LocalTyp

    val codom = LocalTyp

    val typ = LocalTyp ->: LocalTyp

    def subs(x: Term, y: Term) = this

    def newobj = this

    def act(y: LocalTerm) = y match {
      case Literal(b) => Literal(a + b)
      case Comb(f, Literal(b), v) if f == sum => sum(Literal(a + b))(v)
      case p => Comb(sum, Literal(a), p)
    }
  }

  /**
    * returns function x + _ where x is not a literal and is indecomposable under sum
    */
  case class AddTerm(x: LocalTerm) extends Func[LocalTerm, LocalTerm] {
//    println(s"addterm $x")

    val dom = LocalTyp

    val codom = LocalTyp

    val typ = LocalTyp ->: LocalTyp

    def subs(x: Term, y: Term) = this

    def newobj = this

    def act(y: LocalTerm) = {
//      println(s"AddTerm($x) applied to $y")
      y match {
        case Literal(a) => Comb(sum, Literal(a), x)
        case Comb(f, Literal(a), v) if f == sum => sum(Literal(a))(sum(x)(v))
        case s: SigmaTerm => x +: s
        case _ =>
          LitProd.addReduce(x, y) getOrElse
          (if (y == x) LitProd(two, x)
           else SigmaTerm(Set(x, y)))
      }
    }
  }

  def funcSum(f: LocalTerm => LocalTerm, g: LocalTerm => LocalTerm) = {
    val x = LocalTyp.obj
    lmbda(x)(sum(f(x))(g(x)))
  }

  case class AdditiveMorphism[U <: LocalTerm with Subs[U]](
      base: Func[LocalTerm, U], op: (U, U) => U)
      extends Func[LocalTerm, LocalTerm] {
    val dom = LocalTyp

    val codom = base.codom

    val typ = LocalTyp ->: codom

    def subs(x: Term, y: Term) = AdditiveMorphism(base.subs(x, y), op)

    def newobj = AdditiveMorphism(base.newobj, op)

    def act(x: LocalTerm) = x match {
      case Comb(f, u, v) if f == sum => op(base(u), base(v))
      case SigmaTerm(elems) => (elems map ((u) => base(u))).reduce(op)
      case _ => base(x)
    }
  }

  @annotation.tailrec
  final def posPower(x: LocalTerm,
                     n: Int,
                     accum: LocalTerm = Literal(one)): LocalTerm = {
    require(
        n >= 0, s"attempted to compute negative power $n of $x recursively")
    if (n == 0) accum
    else posPower(x, n - 1, prod(x)(accum))
  }

  /**
    * returns power of x by n,
    * in generality an error for negative n;
    * should be overridden in fields, where negative powers are meaningful
    */
  def power(x: LocalTerm, n: Int): LocalTerm =
    posPower(x, n)

  case object prod extends Func[LocalTerm, Func[LocalTerm, LocalTerm]] {
    val dom = LocalTyp

    val codom = LocalTyp ->: LocalTyp

    val typ = dom ->: codom

    def subs(x: Term, y: Term) = this

    def newobj = this

    def act(x: LocalTerm) = x match {
      case Literal(a) =>
        if (a == one) {
          val x = LocalTyp.obj
          lmbda(x)(x)
        } else
          AdditiveMorphism(multLiteral(a),
                           (x: LocalTerm, y: LocalTerm) => sum(x)(y))
      case Comb(op, u, v) if op == prod =>
        composition(prod(u), prod(v))
      case Comb(op, u, v) if op == sum =>
        funcSum(prod(u), prod(v))
      case s @ SigmaTerm(terms) =>
        (terms map ((t) => prod(t))).reduce(funcSum)
      case Reciprocal(x) =>
        multTerm(Reciprocal(x)) // do not regard reciprocals as Pi's, or we get an infinite loop.
      case p: PiTerm =>
        if (p.isComposite) composition(prod(p.head), prod(p.tail))
        else prod(p.head)
      case y => multTerm(y)
    }

    override def toString = "prod"
  }

  case class multLiteral(b: A) extends Func[LocalTerm, LocalTerm] {
    val x = Literal(b)

    val dom = LocalTyp

    import Reciprocal.{base, expo}

    val codom = LocalTyp

    val typ = LocalTyp ->: LocalTyp

    def subs(x: Term, y: Term) = this

    def newobj = this

    def act(y: LocalTerm) = y match {
      case Literal(a) => Literal(b * a)
      case Comb(f, Literal(a), v) if f == prod => prod(Literal(b * a))(v)
      case Comb(f, u, v) if f == sum => sum(prod(x)(u))(prod(x)(v))
      case SigmaTerm(elems) =>
        (elems map ((u) => prod(x)(u)))
          .reduce((a: LocalTerm, b: LocalTerm) => sum(a)(b))
      case p => Comb(prod, x, p)
    }
  }

  case class multTerm(x: LocalTerm) extends Func[LocalTerm, LocalTerm] {
    val dom = LocalTyp

    import Reciprocal.{base, expo}

    val codom = LocalTyp

    val typ = LocalTyp ->: LocalTyp

    def subs(x: Term, y: Term) = this

    def newobj = this

    def act(y: LocalTerm) = y match {
      case Literal(a) => prod(Literal(a))(x)
      case Comb(f, Literal(a), v) if f == prod => prod(Literal(a))(prod(x)(v))
      case Comb(f, u, v) if f == sum => sum(prod(x)(u))(prod(x)(v))
      case SigmaTerm(elems) =>
        (elems map ((u) => prod(x)(u)))
          .reduce((a: LocalTerm, b: LocalTerm) => sum(a)(b))
      case p: PiTerm => x *: p
      case `x` => PiTerm(Map(base(x) -> 2 * expo(x)))
      case _ => PiTerm(Map(base(x) -> expo(x), base(y) -> expo(y)))
    }
  }

  implicit val crigStructure: CRig[LocalTerm] = new CRig[LocalTerm] {
    val zero = Literal(rig.zero)

    val one = Literal(rig.one)

    def plus(x: LocalTerm, y: LocalTerm) = self.sum(x)(y)

    def times(x: LocalTerm, y: LocalTerm) = self.prod(x)(y)
  }
}

object SymbolicCRig extends LiteralParser {

  def parse(typ: Typ[Term])(str: String): Option[Term] = typ match {
    case FuncTyp(a: SymbolicCRig[u], FuncTyp(b, c)) if a == b && b == c =>
      str match {
        case x if x == a.sum.toString() => Some(a.sum)
        case x if x == a.prod.toString() => Some(a.prod)
        case _ => None
      }
    case tp: SymbolicCRig[a] => Try(tp.Literal.fromInt(str.toInt)).toOption
    case _ => None
  }

  def literal(term: Term) = term.typ match {
    case tp: SymbolicCRig[a] =>
      term match {
        case tp.Literal(a) => Some(a.toString)
        case _ => None
      }
    case _ => None
  }
}
