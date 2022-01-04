package com.cliffc.aa.type;

import java.util.HashMap;

// Alias Bits supporting a lattice; immutable; hash-cons'd.
//
// Alias Bits map 1-to-1 with allocation sites (NewNode)
public class BitsAlias extends Bits<BitsAlias> {
  // Intern: lookup and return an existing Bits or install in hashmap and
  // return a new Bits.  Overridden in subclasses to make type-specific Bits.
  private static final HashMap<BitsAlias,BitsAlias> INTERN = new HashMap<>();
  private static BitsAlias FREE=null;
  @Override BitsAlias make_impl(int con, long[] bits ) {
    BitsAlias b1 = FREE;
    if( b1 == null ) b1 = new BitsAlias();
    else FREE = null;
    b1.init(con,bits);
    BitsAlias b2 = INTERN.get(b1);
    if( b2 != null ) { FREE = b1; return b2; }
    else { INTERN.put(b1,b1); return b1; }
  }

  static final Bits.Tree<BitsAlias> TREE = new Bits.Tree<>();
  @Override public Tree<BitsAlias> tree() { return TREE; }
  public static final int ALLX;
  public static BitsAlias ALL, ANY, NALL, NIL, XNIL, EMPTY;

  static {
    // The All-Memory alias class
    ALLX = TREE.split(0);        // Split from 0
    NALL = new BitsAlias().make_impl(ALLX,null); // All aliases, no nil
    ALL  = NALL.meet_nil();      // All aliases, with a low nil
    ANY  = ALL.dual();           // Precompute dual
    NIL  = make0(0);             // Ugly but NIL has a dual, and this is "low" NIL
    XNIL = NIL.dual();           //
    EMPTY= new BitsAlias().make(); // No bits; its its own dual
  }
  // True if kid is a child or equal to parent
  public static boolean is_parent( int par, int kid ) { return TREE.is_parent(par,kid); }
  // True if this alias has been split thus has children
  public static boolean is_parent( int idx ) { return TREE.is_parent(idx); }
  // Return parent alias from child alias.
  public static int parent( int kid ) { return TREE.parent(kid); }
  // Return two child aliases from the one parent at ary[1] and ary[2]
  public static int[] get_kids( int par ) { return TREE.get_kids(par); }
  // Fast reset of parser state between calls to Exec
  public static void init0() { TREE.init0(); }
  public static void reset_to_init0() { TREE.reset_to_init0(); }
  // Iterate over children
  public static int next_kid( int alias, int kid ) { return TREE.next_kid(alias,kid); }

  @Override public BitsAlias ALL() { return ALL; }
  @Override public BitsAlias ANY() { return ANY ; }
  @Override public BitsAlias EMPTY() { return EMPTY ; }

  public static BitsAlias make0( int bit ) { return NALL.make(bit); }
  public BitsAlias or( int bit ) { return set(bit); }

  public static int  new_alias(int par) { return set_alias(par); }
  private static int set_alias(int par) { return TREE.split(par); }
  public static void free(int fidx) { TREE.free(fidx); }

  BitsAlias widen() { return NALL; }
}
