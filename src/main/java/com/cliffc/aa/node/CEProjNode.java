package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;

// Proj control
public class CEProjNode extends CProjNode {
  final TypeFunSig _sig;
  public CEProjNode( CallNode call, TypeFunSig sig ) { super(call); _sig = sig; }
  @Override public String xstr() { return "CEProj"; }
  @Override public Type value(GVNGCM.Mode opt_mode) {
    if( _uses._len<1 ) return Type.CTRL;
    return good_call(val(0),_uses.at(0)) ? Type.CTRL : Type.XCTRL;
  }

  // Never equal to another CEProj, since Call-Graph *edges* are unique
  @Override public int hashCode() { return super.hashCode()+(_sig==null ? 0 : _sig._hash); }
  @Override public boolean equals(Object o) { return this==o; }

  static boolean good_call(Type tcall, Node ftun ) {
    if( !(tcall instanceof TypeTuple) ) return !tcall.above_center();
    TypeTuple ttcall = (TypeTuple)tcall; // Call type tuple
    if( ttcall.at(0)!=Type.CTRL ) return false; // Call not executing
    if( ftun instanceof ThunkNode ) return true; // Thunk call is OK by design
    FunNode fun = (FunNode)ftun;
    if( fun._thunk_rhs ) return true; // Thunk call is OK by design
    TypeFunPtr tfp = CallNode.ttfp(ttcall);
    if( tfp.fidxs().above_center() ) return false; // Call not executing yet
    if( !tfp.fidxs().test_recur(fun._fidx) )
      return false;             // Call not executing this wired path

    // Argument count mismatch
    TypeTuple formals = fun._sig._formals;
    if( ttcall.len()-2/*minus fun, minus esc*/ != formals.len() ) return false;

    // Cannot use the obvious argument check "actual.isa(formal)"!!!!!

    // If the actual is higher than formal (not even above_center), but then
    // falls during Opto, this type would LIFT from Ctrl to XCTRL.  Can only
    // test for static properties (e.g. argument count, or constant ALL
    // arguments).


    //// Check good args
    //TypeMem tmem = (TypeMem)ttcall.at(AA.MEM_IDX);
    //for( int i=AA.MEM_IDX; i<formals.len(); i++ ) {
    //  Type formal = formals.at(i);
    //  Type actual0= ttcall.at(i);
    //  if( actual0==Type.ANY ) continue; // Allow ignored args
    //  Type actual = tmem.sharptr(actual0);
    //  if( formal.isa(actual) ) continue; // Low but sane
    //  if( actual.isa(formal) &&
    //      (!actual.above_center() ||
    //       actual==Type.XNIL ||
    //       formal.dual().isa(actual))  )
    //    continue;
    //  return false;
    //}
    return true;
  }
}
