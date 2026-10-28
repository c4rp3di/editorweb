package org.junit;
public class Assert {
  public static void assertEquals(Object a, Object b) { if (a == null ? b != null : !a.equals(b)) throw new AssertionError("expected="+a+" actual="+b); }
  public static void assertEquals(long a, long b) { if (a != b) throw new AssertionError("expected="+a+" actual="+b); }
  public static void assertTrue(boolean v) { if (!v) throw new AssertionError("expected true"); }
  public static void assertFalse(boolean v) { if (v) throw new AssertionError("expected false"); }
  public static void assertNotNull(Object v) { if (v == null) throw new AssertionError("expected non-null"); }
}
