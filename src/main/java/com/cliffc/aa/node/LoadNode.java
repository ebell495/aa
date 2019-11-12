package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

// Load a named field from a struct.  Does it's own nil-check testing.  Loaded
// value depends on the struct typing.
public class LoadNode extends Node {
  private final String _fld;
  private final int _fld_num;
  private final Parse _bad;
  private LoadNode( Node ctrl, Node mem, Node adr, String fld, int fld_num, Parse bad ) {
    super(OP_LOAD,ctrl,mem,adr);
    _fld = fld;
    _fld_num = fld_num;
    _bad = bad;
  }
  public LoadNode( Node ctrl, Node mem, Node adr, String fld , Parse bad ) { this(ctrl,mem,adr,fld,-1,bad); }
  public LoadNode( Node ctrl, Node mem, Node adr, int fld_num, Parse bad ) { this(ctrl,mem,adr,null,fld_num,bad); }
  String xstr() { return "."+(_fld==null ? ""+_fld_num : _fld); } // Self short name
  String  str() { return xstr(); }      // Inline short name
  private Node ctl() { return in(0); }
  private Node mem() { return in(1); }
  private Node adr() { return in(2); }
  private Node nil_ctl(GVNGCM gvn) { return set_def(0,null,gvn); }
  private Node set_mem(Node a, GVNGCM gvn) { return set_def(1,a,gvn); }
  private void set_adr(Node a, GVNGCM gvn) { set_def(2,a,gvn); }

  @Override public Node ideal(GVNGCM gvn) {
    Node ctrl = ctl();
    Node mem  = mem();
    Node addr = adr();

    // Loads against a NewNode cannot NPE, cannot fail, always return the input
    if( mem instanceof MemMergeNode && addr.in(0) == mem.in(1).in(0) && addr.in(0) instanceof NewNode )
      mem = mem.in(1);

    // Loads against a NewNode cannot NPE, cannot fail, always return the input
    if( addr.in(0) == mem.in(0) && addr.in(0) instanceof NewNode ) {
      NewNode nnn = (NewNode)addr.in(0);
      int idx = nnn._ts.find(_fld,_fld_num);  // Find the named field
      if( idx != -1 ) return nnn.fld(idx);    // Field value
      // Broken load-vs-new
    }

    // Split aliases on inputs
    Type tadr0= gvn.type(addr);
    if( tadr0 instanceof TypeMemPtr ) {
      Node nmem = mem.split_memory_use(gvn,((TypeMemPtr)tadr0)._aliases);
      if( nmem != null ) {      // If progress
        set_mem(nmem,gvn);      // Sharpen memory edge and try again
        return this;
      }
    }

    // Load from a memory Phi; split through in an effort to sharpen the memory.
    if( mem instanceof PhiNode && ctrl == null && addr instanceof ProjNode && addr.in(0) instanceof NewNode ) {
      // Only split thru function args if no unknown_callers, and must make a Parm not a Phi
      if( !(mem instanceof ParmNode) ) {
        Node lphi = new PhiNode(((PhiNode)mem)._badgc,mem.in(0));
        for( int i=1; i<mem._defs._len; i++ )
          lphi.add_def(gvn.xform(new LoadNode(null,mem.in(i),addr,_fld,_fld_num,_bad)));
        return lphi;
      }
    }

    // Loads against an equal store; cannot NPE since the Store did not.
    StoreNode st=null;
    if( mem instanceof StoreNode && addr == (st=((StoreNode)mem)).adr() ) {
      if( _fld.equals(st._fld) && _fld_num == st._fld_num && st.err(gvn)==null )
        return st.val();
      // TODO: Else can use field-level aliasing to by pass.  Needs a
      // field-level alias notion on memory edges.
    }

    // Bypass unrelated Stores
    if( st != null && st.err(gvn)==null &&
        ( st._fld_num != _fld_num || (_fld != null && !_fld.equals(st._fld_num)) ))
      return set_mem(st.mem(),gvn);
    
    if( ctrl==null || gvn.type(ctrl)!=Type.CTRL )
      return null;              // Dead load, or a no-control-no-fail load
    if( addr.is_forward_ref() ) return null;
    Type t = gvn.type(addr);    // Address type

    // Lift control on Loads as high as possible... and move them over
    // to a CastNode (to remove nil-ness) and remove the control.
    if( !t.must_nil() ) // No nil, no need for ctrl
      // remove ctrl; address already casts-away-nil
      return nil_ctl(gvn);

    // Looking for a nil-check pattern:
    //   this.0->dom*->True->If->addr
    //   this.1->[Cast]*-------/   Cast(s) are optional
    // If found, convert to this pattern:
    //   this.0      ->True->If->addr
    //   this.1->Cast/---------/
    // Where the cast-away-nil is local and explicit
    Node baseaddr = addr;
    while( baseaddr instanceof CastNode ) baseaddr = baseaddr.in(1);
    final Node fbaseaddr = baseaddr;

    Node tru = ctrl.walk_dom_last(n ->
                                  n instanceof CProjNode && ((CProjNode)n)._idx==1 &&
                                  n.in(0) instanceof IfNode &&
                                  n.in(0).in(1) == fbaseaddr );
    if( tru==null ) return null;
    assert !(tru==ctrl && addr != baseaddr) : "not the immediate location or we would be not-nil already";

    if( !(t instanceof TypeMemPtr) )
      return null; // below a nil (e.g. Scalar), do nothing yet
    Type tnz = t.join(TypeMemPtr.OOP); // Remove nil choice
    set_adr(gvn.xform(new CastNode(tru,baseaddr,tnz)),gvn);
    return nil_ctl(gvn);
  }

  @Override public Type value(GVNGCM gvn) {
    Type adr = gvn.type(adr()).base();
    if( adr.isa(TypeMemPtr.OOP0.dual()) ) return Type.XSCALAR;
    if( adr.must_nil() ) return Type.SCALAR; // Not provable not-nil, so fails
    if( TypeMemPtr.OOP0.isa(adr) ) return Type.SCALAR; // Very low, might be any address
    if( adr.is_forward_ref() ) return Type.SCALAR;
    if( !(adr instanceof TypeMemPtr) )
      return adr.above_center() ? Type.XSCALAR : Type.SCALAR;
    TypeMemPtr tadr = (TypeMemPtr)adr;

    Type mem = gvn.type(mem());     // Memory
    if( !(mem instanceof TypeStruct) ) {
      if( !(mem instanceof TypeMem) ) // Nothing sane
        return mem.above_center() ? Type.XSCALAR : Type.SCALAR;
      TypeObj obj = ((TypeMem)mem).ld(tadr);
      TypeObj obj2 = (TypeObj)obj.meet(tadr._obj); // Loaded obj is in expected type bounds of pointer?
      mem = obj2.base();
    }

    if( mem instanceof TypeStruct ) {
      TypeStruct ts = (TypeStruct)mem;
      int idx = ts.find(_fld,_fld_num);  // Find the named field
      if( idx != -1 ) return ts.at(idx); // Field type
      // No such field
      return mem.above_center() ? Type.XSCALAR : Type.SCALAR;
    }
    return Type.SCALAR;        // No loading from e.g. Strings
  }

  @Override public String err(GVNGCM gvn) {
    Type t = gvn.type(adr());
    while( t instanceof TypeName ) t = ((TypeName)t)._t;
    if( t.must_nil() ) return bad("Struct might be nil when reading");
    if( !(t instanceof TypeMemPtr) )
      return bad("Unknown"); // Not a pointer, cannot load a field
    TypeMemPtr t3 = (TypeMemPtr)t;
    Type t4 = gvn.type(mem());
    if( t4 instanceof TypeMem ) {         // Memory or Struct, for various errors
      TypeMem t5 = (TypeMem)t4;           // Should be memory
      Type t6 = t5.ld(t3);                // General load from memory
      if( !(t6 instanceof TypeStruct) ) { // No fields, so memory or ptr is in-error
        Type t7 = t3._obj.base();
        if( t7 instanceof TypeStruct ) {
          t4 = t7;
        } else {
          return bad("Unknown");
        }
      } else t4 = t6;
    }
    if( !(t4 instanceof TypeStruct) || ((TypeStruct)t4).find(_fld,_fld_num) == -1 )
      return bad("Unknown");
    return null;
  }
  private String bad( String msg ) {
    String f = _fld==null ? ""+_fld_num : _fld;
    String f2 = msg+" field '."+f+"'";
    return _bad==null ? f2 : _bad.errMsg(f2);
  }
  @Override public Type all_type() { return Type.SCALAR; }
  @Override public int hashCode() { return super.hashCode()+(_fld==null ? _fld_num : _fld.hashCode()); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof LoadNode) ) return false;
    LoadNode ld = (LoadNode)o;
    return _fld_num == ld._fld_num && (_fld==null ? ld._fld==null : _fld.equals(ld._fld));
  }
}
