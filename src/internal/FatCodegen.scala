package scala.virtualization.lms
package internal

import util.GraphUtil
import java.io.{File, PrintWriter}

trait GenericFatCodegen extends GenericNestedCodegen with FatScheduling {
  val IR: Expressions with Effects with FatExpressions
  import IR._  
  
  case class Combine(a: List[Exp[Any]]) extends Exp[Any]

  // these are needed by loop fusion. they should live elsewhere.
  def unapplySimpleIndex(e: Def[Any]): Option[(Exp[Any], Exp[Int])] = None
  def unapplySimpleCollect(e: Def[Any]): Option[Exp[Any]] = None
  def shouldApplyFusion(currentScope: List[TTP])(result: Exp[Any]): Boolean = true


  override def emitBlockFocused(result: Exp[Any])(implicit stream: PrintWriter): Unit = {
    var currentScope = innerScope.map(fatten)
    currentScope = getFatSchedule(currentScope)(result) // clean things up!
    emitFatBlockFocused(currentScope)(result)
  }

  def emitFatBlockFocused(currentScope: List[TTP])(result: Exp[Any])(implicit stream: PrintWriter): Unit = {
    // do what super does, modulo fat stuff
    focusExactScopeFat(currentScope)(result) { levelScope => 
      for (TTP(syms, rhs) <- levelScope) {
        emitFatNode(syms, rhs)
      }
    }
  }

  def focusExactScopeFat[A](currentScope: List[TTP])(result: Exp[Any])(body: List[TTP] => A): A = {
    
    val saveInner = innerScope
    
    val e1 = currentScope
    shallow = true
    val e2 = getFatSchedule(currentScope)(result) // shallow list of deps (exclude stuff only needed by nested blocks)
    shallow = false

    // TODO: make sure currentScope schedule respects antidependencies

    // shallow is 'must outside + should outside' <--- currently shallow == deep for lambdas, meaning everything 'should outside'
    // bound is 'must inside'

    // find transitive dependencies on bound syms, including their defs (in case of effects)
    val bound = e1.flatMap(z => boundSyms(z.rhs))
    val g1 = getFatDependentStuff(currentScope)(bound)
    
    val levelScope = e1.filter(z => (e2 contains z) && !(g1 contains z)) // shallow (but with the ordering of deep!!) and minus bound

/*
    // sanity check to make sure all effects are accounted for
    result match {
      case Def(Reify(x, u, effects)) =>
        val actual = levelScope.filter(effects contains _.sym)
        assert(effects == actual.map(_.sym), "violated ordering of effects: expected \n    "+effects+"\nbut got\n    " + actual)
      case _ =>
    }
*/
    val innerScope2 = e1 diff levelScope // delay everything that remains


    innerScope = innerScope2 flatMap { 
      case TTP(List(sym), ThinDef(rhs)) => List(TP(sym, rhs))
      case e => 
        val z = innerScope.filter(e.lhs contains _.sym)
        if (z.length != e.lhs.length)
          println("TROUBLE: couldn't get syms " + e.lhs + ", found only " + z)
        z
    }

    val rval = body(levelScope)
    
    innerScope = saveInner
    rval
  }


  def emitFatNode(sym: List[Sym[Any]], rhs: FatDef)(implicit stream: PrintWriter): Unit = rhs match {
    case ThinDef(Reflect(s, u, effects)) => emitFatNode(sym, ThinDef(s)) // call back into emitFatNode, not emitNode
    case ThinDef(a) => emitNode(sym(0), a)
    case _ => system.error("don't know how to generate code for: "+rhs)
  }

  def emitFatBlock(rhs: List[Exp[Any]])(implicit stream: PrintWriter): Unit = {
    emitBlock(Combine(rhs))
  }

  def focusFatBlock[A](rhs: List[Exp[Any]])(body: => A): A = {
    focusBlock(Combine(rhs))(body)
  }


}