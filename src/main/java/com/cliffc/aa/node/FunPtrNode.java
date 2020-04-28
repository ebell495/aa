package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

// See CallNode and FunNode comments. The FunPtrNode converts a RetNode into a
// TypeFunPtr with a constant fidx and variable displays.  Used to allow 1st
// class functions to be passed about.
public final class FunPtrNode extends Node {
  private final String _referr;
  public  FunPtrNode( RetNode ret, Node display ) { this(null,ret,display); }
  // For forward-refs only; super weak display & function.
  private FunPtrNode( String referr, RetNode ret, Node display ) {
    super(OP_FUNPTR,ret,display);
    _referr = referr;
  }
  public RetNode ret() { return (RetNode)in(0); }
  public Node display(){ return in(1); }
  public FunNode fun() { return ret().fun(); }
  // Self short name
  @Override String xstr() {
    if( is_dead() ) return "*fun";
    int fidx = ret()._fidx;    // Reliably returns a fidx
    FunNode fun = FunNode.find_fidx(fidx);
    return "*"+(fun==null ? ""+fidx : fun.name());
  }
  // Inline longer name
  @Override String str() {
    if( is_dead() ) return "DEAD";
    RetNode ret = ret();
    if( ret.is_copy() ) return "gensym:"+xstr();
    FunNode fun = ret.fun();
    return fun==null ? xstr() : fun.str();
  }

  @Override public Node ideal(GVNGCM gvn, int level) {
    if( is_forward_ref() ) return null;
    RetNode ret = ret();
    FunNode fun = ret.is_copy() ? FunNode.find_fidx(ret._fidx) : ret.fun();
    if( display() !=null && (ret.is_copy() || fun.parm(0)==null) ) {
      set_def(1,null,gvn);      // No display needed
      return this;
    }
    return null;
  }
  @Override public TypeFunPtr value(GVNGCM gvn) {
    if( !(in(0) instanceof RetNode) )
      return TypeFunPtr.EMPTY;
    RetNode ret = ret();
    Node disp = display();
    TypeMemPtr tdisp = disp==null ? TypeFunPtr.NO_DISP : (TypeMemPtr)gvn.type(disp);
    return TypeFunPtr.make(ret._fidx,ret._nargs,tdisp);
  }

  @Override public TypeMem live( GVNGCM gvn) {
    // Pre-GCP, if the function is anywhere alive it might be used in a call
    // and thus demands all the memory that the CallEpi demands.
    // Post-GCP, all things are resolved and normal liveness flows.
    return gvn._opt_mode < 2 ? TypeMem.FULL : super.live(gvn);
  }
  // A function pointer can be applied at a Call, in which case the associated
  // Ret demands everything the CallEpi demands.  Until GCP we assume this
  // FunPtr might end up at any Call.
  @Override public boolean basic_liveness() { return false; }

  // Note: graph structure must be in place before calling
  @Override public TypeFunPtr all_type() { return TypeFunPtr.GENERIC_FUNPTR; }
  @Override public String toString() { return super.toString(); }
  // Return the op_prec of the returned value.  Not sensible except when called
  // on primitives.
  @Override public byte op_prec() { return ret().op_prec(); }

  // True if function is uncalled (but possibly returned or stored as
  // a constant).  Such code is not searched for errors.
  @Override boolean is_uncalled(GVNGCM gvn) {
    return !is_forward_ref() && ((TypeTuple)gvn.type(ret())).at(0)==Type.XCTRL;
  }
  // Instead of returning the pre-call memory on true, returns self.
  // Changes as the graph changes, because works purely off of graph shape.
  @Override Node is_pure_call() {
    // See if the RetNode points to a Parm:mem (so no mods on memory).
    RetNode ret = ret();
    if( ret.is_copy() ) return null;
    FunNode fun = ret.fun();
    Node mem = ret.mem();
    if( mem.in(0)==fun && mem instanceof ParmNode ) return this; // Parm:mem on fun, no mods to memory
    return null;
  }

  // A forward-ref is an assumed unknown-function being used before being
  // declared.  Hence we want a callable function pointer, but have no defined
  // body (yet).  Make a function pointer that takes/ignores all args, and
  // returns a scalar.
  public static FunPtrNode forward_ref( GVNGCM gvn, String name, Parse unkref ) {
    FunNode fun = gvn.init(new FunNode(name));
    RetNode ret = gvn.init(new RetNode(fun,gvn.con(TypeMem.MEM),gvn.con(Type.SCALAR),gvn.con(TypeRPC.ALL_CALL),fun));
    return new FunPtrNode(unkref.forward_ref_err(fun),ret,gvn.con(TypeMemPtr.DISPLAY_PTR));
  }

  // True if this is a forward_ref
  @Override public boolean is_forward_ref() { return _referr!=null; }

  // 'this' is a forward reference, probably with multiple uses (and no inlined
  // callers).  Passed in the matching function definition, which is brand new
  // and has no uses.  Merge the two.
  public void merge_ref_def( GVNGCM gvn, String tok, FunPtrNode def, TypeMemPtr disp_ptr ) {
    FunNode rfun = fun();
    FunNode dfun = def.fun();
    assert rfun._defs._len==2 && rfun.in(0)==null && rfun.in(1) == Env.ALL_CTRL; // Forward ref has no callers
    assert dfun._defs._len==2 && dfun.in(0)==null;
    assert def ._uses._len==0;  // Def is brand new, no uses

    // Make a function pointer based on the original forward-ref fidx, but with
    // the known types.
    FunNode.FUNS.setX(dfun._fidx,null); // Untrack dfun by old fidx
    gvn.unreg(dfun);  dfun._fidx = rfun._fidx;  gvn.rereg(dfun,Type.CTRL);
    FunNode.FUNS.setX(dfun._fidx,dfun); // Track FunNode by fidx
    
    RetNode dret = def.ret();
    Type tret = gvn.unreg(dret);
    dret._fidx  = rfun._fidx ;
    dret._nargs = rfun.nargs();
    gvn.rereg(dret,tret);
    
    // Replace the forward_ref with the def.
    gvn.subsume(this,def);
    dfun.bind(tok);
  }

  @Override public String err(GVNGCM gvn) { return is_forward_ref() ? _referr : null; }
}
