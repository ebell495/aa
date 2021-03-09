package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.NonBlockingHashMap;

import static com.cliffc.aa.AA.MEM_IDX;
import static com.cliffc.aa.Env.GVN;

// See CallNode and FunNode comments. The FunPtrNode converts a RetNode into a
// TypeFunPtr with a constant fidx and variable displays.  Used to allow 1st
// class functions to be passed about.
public final class FunPtrNode extends Node {
  public String _name;          // Optional for debug only
  private ErrMsg _referr;       // Forward-ref error, cleared after defining
  final boolean _mid_def; // H-M mid-definition of a recursive fcn; true means not "Fresh";

  // Every var use that results in a function, so actually only these FunPtrs,
  // needs to make a "fresh" copy before unification.  "Fresh" makes a
  // structural copy of the TVar, keeping TVars from Nodes currently in-scope
  // as-is, and making structural copies of out-of-scope TVars.  The only
  // interesting thing is when an out-of-scope TVar uses the same TVar
  // internally in different parts - the copy replicates this structure.  When
  // unified, it forces equivalence in the same places.
  public  FunPtrNode( String name, RetNode ret, Env e ) { this(name,null,ret,e==null ? Node.con(TypeMemPtr.NO_DISP) : e._scope.ptr(),false); }
  public  FunPtrNode( RetNode ret, Node display ) { this(null,null,ret,display,true); }
  // For forward-refs only; super weak display & function.
  private FunPtrNode( String name, ErrMsg referr, RetNode ret, Node display, boolean mid_def ) {
    super(OP_FUNPTR,ret,display);
    _name = name;
    _referr = referr;
    _mid_def = mid_def;
    if( _mid_def ) { TV2 tv = _tvar.get_fresh();  _tvar.reset(this); _tvar = tv; }
  }
  public FunPtrNode make_fresh() {
    assert !tvar().is_fresh();
    FunPtrNode fptr = new FunPtrNode(_name,_referr,ret(),display(),false);
    fptr._tvar.reset(fptr);
    fptr._tvar = TV2.make_fresh(tvar(),"FunPtr_make_fresh");
    return fptr;
  }
  public RetNode ret() { return (RetNode)in(0); }
  public Node display(){ return in(1); }
  public FunNode fun() { return ret().fun(); }
  public FunNode xfun() { RetNode ret = ret(); return ret.in(4) instanceof FunNode ? ret.fun() : null; }
  // Self short name
  @Override public String xstr() {
    if( is_dead() || _defs._len==0 ) return "*fun";
    int fidx = ret()._fidx;    // Reliably returns a fidx
    FunNode fun = FunNode.find_fidx(fidx);
    return (_mid_def ? "@" : "*")+(fun==null ? ""+fidx : fun.name());
  }
  // Inline longer name
  @Override String str() {
    if( is_dead() ) return "DEAD";
    if( _defs._len==0 ) return "MAKING";
    RetNode ret = ret();
    if( ret.is_copy() ) return "gensym:"+xstr();
    FunNode fun = ret.fun();
    return fun==null ? xstr() : fun.str();
  }

  // Debug only: make an attempt to bind name to a function
  public void bind( String tok ) {
    assert _name==null || _name.equals(tok); // Attempt to double-bind
    _name = tok;
    fun().bind(tok);
  }

  @Override public Node ideal_reduce() {
    if( is_forward_ref() ) return null;

    // Display is known dead?  Yank it.
    Node dsp = display();
    Type tdsp = dsp._val;
    if( tdsp instanceof TypeMemPtr && ((TypeMemPtr)tdsp)._obj==TypeObj.UNUSED && !(dsp instanceof ConNode) )
      return set_def(1,Env.ANY); // No display needed

    // Remove unused displays.  Track uses; Calling with no display is OK.
    // Uses storing the FPTR and passing it along still require a display.
    if( GVN._opt_mode._CG && !(dsp instanceof ConNode) && !display_used() )
      return set_def(1,Env.ANY); // No display needed
    return null;
  }
  // Called if Display goes unused
  @Override public void add_flow_use_extra(Node chg) {
    Type tdsp = display()._val;
    if( tdsp instanceof TypeMemPtr && ((TypeMemPtr)tdsp)._obj==TypeObj.UNUSED )
      Env.GVN.add_reduce(this);
  }

  // Is the display used?
  private boolean display_used() {
    for( Node call : _uses ) {
      if( call instanceof CallNode ) {
        if( call._defs.find(e->e==this) < call._defs._len-1 )
          return true;          // Call-use other than the last position is using the display
      } else {
        return true;            // Anything other than a Call is using the display
      }
    }
    return false;
  }


  @Override public Type value(GVNGCM.Mode opt_mode) {
    if( !(in(0) instanceof RetNode) )
      return TypeFunPtr.EMPTY;
    return TypeFunPtr.make(ret()._fidx,ret()._nargs,display()._val);
  }

  @Override public TypeMem live(GVNGCM.Mode opt_mode) {
    // Pre-GCP, might be used anywhere (still finding the CFG)
    return !opt_mode._CG ? TypeMem.ESCAPE : super.live(opt_mode);
  }
  @Override public TypeMem live_use(GVNGCM.Mode opt_mode, Node def ) {
    return def==ret() ? TypeMem.ANYMEM : TypeMem.ESCAPE;
  }

  @Override public TV2 new_tvar(String alloc_site) {
    TV2 tv = TV2.make("Fun",this,alloc_site);
    if( _mid_def ) return tv;
    return TV2.make_fresh(tv,alloc_site);
  }

  @Override public boolean unify( boolean test ) {
    // Build a HM tvar (args->ret), same as HM.java Lambda does.
    // FunNodes are just argument collections (no return).
    RetNode ret = ret();
    FunNode fun = xfun();
    if( fun==null ) return false;
    TV2 tret = ret.tvar();
    if( tret.is_dead() ) return false;
    assert tret.isa("Ret"); // Ret  is always a Ret

    // Check for progress before allocation
    TV2 tvar = tvar();
    if( tvar.is_dead() ) return false;
    assert tvar.is_fresh() || tvar.isa("Fun"); // Self is always a Fresh or Fun
    TV2 tvx = tvar.is_fresh() ? tvar.get_fresh() : tvar;
    TV2 tvar_args = tvx.get("Args");
    TV2 tvar_ret  = tvx.get("Ret" );
    Node[] parms = fun.parms();
    parms[0] = fun;
    if( tvar_args!=null && tvar_args.eq(parms) && tvar_ret==tret ) return false; // Equal parts
    // Build function arguments; "fun" itself is just control.
    TV2 targ = TV2.make("Args",fun,"FunPtr_unify_Args",parms);
    NonBlockingHashMap<Object,TV2> args = new NonBlockingHashMap<Object,TV2>(){{ put("Args",targ);  put("Ret",tret); }};
    TV2 tfun = TV2.make("Fun",this,"FunPtr_unify_Fun",args);
    return tvx.unify(tfun,test);
  }

  // Filter out all the wrong-arg-count functions
  public Node filter( int nargs ) {
    // User-nargs are user-visible #arguments.
    // Fun-nargs include ctrl, memory & the display, hence the +3.
    return fun().nargs() == nargs+3 ? this : null;
  }

  // Return the op_prec of the returned value.  Not sensible except when called
  // on primitives.
  @Override public byte op_prec() { return fun()._op_prec; }

  // Instead of returning the pre-call memory on true, returns self.
  // Changes as the graph changes, because works purely off of graph shape.
  @Override Node is_pure_call() {
    // See if the RetNode points to a Parm:mem (so no mods on memory).
    RetNode ret = ret();
    if( ret.is_copy() ) return null;
    FunNode fun = ret.fun();
    if( fun.noinline() ) return null; // Disallow if no-inline
    Node mem = ret.mem();
    if( mem.in(0)==fun && mem instanceof ParmNode ) return this; // Parm:mem on fun, no mods to memory
    return null;
  }

  // A forward-ref is an assumed unknown-function being used before being
  // declared.  Hence we want a callable function pointer, but have no defined
  // body (yet).  Make a function pointer that takes/ignores all args, and
  // returns a scalar.
  public static FunPtrNode forward_ref( GVNGCM gvn, String name, Parse unkref ) {
    FunNode fun = gvn.init(new FunNode(name)).unkeep();
    RetNode ret = gvn.init(new RetNode(fun,Node.con(TypeMem.MEM),Node.con(Type.SCALAR),Node.con(TypeRPC.ALL_CALL),fun)).unkeep();
    gvn.add_flow(fun);
    gvn.add_flow(ret);
    // Display is limited to any one of the current lexical scopes.
    TypeMemPtr tdisp = TypeMemPtr.make(Env.LEX_DISPLAYS,TypeObj.ISUSED);
    return new FunPtrNode( name, ErrMsg.forward_ref(unkref,name),ret,Node.con(tdisp),true);
  }

  // True if this is a forward_ref
  @Override public boolean is_forward_ref() { return _referr!=null; }

  // 'this' is a forward reference, probably with multiple uses (and no inlined
  // callers).  Passed in the matching function definition, which is brand new
  // and has no uses.  Merge the two.
  public void merge_ref_def( String tok, FunPtrNode def, NewObjNode dsp ) {
    FunNode rfun = fun();
    FunNode dfun = def.fun();
    assert rfun._defs._len==2 && rfun.in(0)==null && rfun.in(1) == Env.ALL_CTRL; // Forward ref has no callers
    assert dfun._defs._len==2 && dfun.in(0)==null;
    assert def ._uses._len==0;  // Def is brand new, no uses
    assert _mid_def && !def._mid_def;
    ProjNode mrg = ProjNode.proj(dsp,MEM_IDX);

    // Make a function pointer based on the original forward-ref fidx, but with
    // the known types.
    FunNode.FUNS.setX(dfun._fidx,null); // Untrack dfun by old fidx
    dfun._fidx = rfun._fidx;
    FunNode.FUNS.setX(dfun._fidx,dfun); // Track FunNode by new fidx

    // Update the fidx
    RetNode dret = def.ret();
    dret.set_fidx(rfun._fidx);
    FunPtrNode fptr = dret.funptr();
    fptr.xval();
    Env.GVN.add_flow_uses(this);

    // The existing forward-ref becomes a normal (not f-ref) but internal
    // FunPtr; the H-M type is NOT Fresh, and forces alignment amongst the
    // recursive uses.  Make a new FunPtr which IS Fresh for future external
    // uses.
    _referr = null;             // No longer a forward ref
    set_def(0,def.in(0));       // Same inputs
    set_def(1,def.in(1));
    dsp.set_def(NewObjNode.def_idx(dsp._ts.find(tok)),def);
    dsp.tvar().reset_at(tok);
    dsp.xval();

    fptr.bind(tok); // Debug only, associate variable name with function
    Env.GVN.add_reduce_uses(this);
    assert Env.START.more_flow(true)==0;
    Env.GVN.iter(GVNGCM.Mode.Parse);
  }

  @Override public int hashCode() { return super.hashCode()+(_mid_def?0:1); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof FunPtrNode) ) return false;
    FunPtrNode fptr = (FunPtrNode)o;
    return _mid_def==fptr._mid_def;
  }

  @Override public ErrMsg err( boolean fast ) { return is_forward_ref() ? _referr : null; }
}
