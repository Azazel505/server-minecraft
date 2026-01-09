  private final Map<String,Object> caches = new java.util.concurrent.ConcurrentHashMap<>();
  private long now() { return java.lang.System.currentTimeMillis(); }
  private boolean rate(Object obj, String op, long ms) {
    long t = now(); String k = "rate:" + System.identityHashCode(obj) + ":" + op;
    Long p = (Long) caches.get(k); if (p != null && (t - p) < ms) return false; caches.put(k, t); return true;
  }
  private String argKey(Object a) {
    if (a == null) return "null";
    Class<?> c = a.getClass();
    if (a instanceof Number || a instanceof CharSequence || a instanceof Boolean) return a.toString();
    if (c.isEnum()) return c.getName() + "#" + ((Enum<?>)a).name();
    return c.getName() + "@" + System.identityHashCode(a);
  }
  private String memoKey(Object target, Method m, Object[] args) {
    StringBuilder sb = new StringBuilder();
    sb.append("memo:").append(System.identityHashCode(target)).append(":").append(m.getName());
    if (args != null) for (Object a : args) sb.append(":").append(argKey(a));
    return sb.toString();
  }
  private boolean looksPure(Method m) {
    try {
      if (m.getReturnType() == Void.TYPE) return false;
      if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) return false;
      String n = m.getName();
      if (n.contains("$md$") || n.contains("$fabric") || n.contains("$mixin") || n.contains("$synthetic") || n.contains("$lambda") || n.contains("$$") || n.contains("_$md$") || n.contains("_fabric_") || n.contains("$asm$") || n.contains("$generated$")) return false;
      Class<?>[] ps = m.getParameterTypes();
      for (Class<?> p : ps) {
        if (java.util.Collection.class.isAssignableFrom(p) || java.util.Map.class.isAssignableFrom(p)) return false;
        if (!p.isPrimitive() && !Number.class.isAssignableFrom(p) && !CharSequence.class.isAssignableFrom(p) && !Boolean.class.isAssignableFrom(p)) return false;
      }
      return n.startsWith("get") || n.startsWith("compute") || n.startsWith("find") || n.startsWith("query") || n.startsWith("resolve") || n.startsWith("fetch") || n.startsWith("estimate");
    } catch (Throwable ignore) { return false; }
  }
  private Object tryMemo(Object target, Method m, Object[] args, long ttlMs) {
    try {
      if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) return null;
      String methodName = m.getName();
      if (methodName.contains("$md$") || methodName.contains("$fabric") || methodName.contains("$mixin") || methodName.contains("$synthetic") || methodName.contains("$lambda") || methodName.contains("$$") || methodName.contains("_$md$") || methodName.contains("_fabric_") || methodName.contains("$asm$") || methodName.contains("$generated$")) return null;
      if (!m.canAccess(target)) { try { m.setAccessible(true); } catch (Throwable t) { return null; } }
      String k = memoKey(target, m, args);
      Object val = caches.get(k);
      String kt = k + ":t";
      Long ts = (Long) caches.get(kt);
      long t = now();
      if (val != null && ts != null && (t - ts) < ttlMs) return val;
      Object r = m.invoke(target, args);
      if (r != null) { caches.put(k, r); caches.put(kt, t); }
      return r;
    } catch (Throwable ignore) { return null; }
  }
  private void optimizeCollections(Object target) {
    if (target instanceof java.util.List<?>) {
      java.util.List<?> l = (java.util.List<?>) target;
      if (rate(target, "list.snapshot", 1500)) { java.util.List<?> snap = new java.util.ArrayList<>(l); caches.put("list.snap:" + System.identityHashCode(target), snap); }
    } else if (target instanceof java.util.Set<?>) {
      java.util.Set<?> s = (java.util.Set<?>) target;
      if (rate(target, "set.snapshot", 2000)) { java.util.Set<?> snap = new java.util.HashSet<>(s); caches.put("set.snap:" + System.identityHashCode(target), snap); }
    } else if (target instanceof java.util.Queue<?>) {
      java.util.Queue<?> q = (java.util.Queue<?>) target;
      if (rate(target, "queue.peek", 750)) {
        try { Method peekM = target.getClass().getMethod("peek"); Object peek = peekM.invoke(target); caches.put("queue.peek:" + System.identityHashCode(target), peek); } catch (Throwable ignore) {}
      }
    } else if (target instanceof java.util.Map<?,?>) {
      caches.put("map.cache:" + System.identityHashCode(target), new java.util.concurrent.ConcurrentHashMap<>());
    }
  }
  private void optimizeGetters(Object target) {
    if (!rate(target, "getters", 3000)) return;
    Method[] ms; try { ms = target.getClass().getMethods(); } catch (Throwable ignore) { return; }
    int count = 0;
    for (Method m : ms) {
      if (count >= 5) break;
      if (!looksPure(m)) continue;
      if (m.getParameterCount() == 0 && (m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
        String cacheKey = "opt_call:" + System.identityHashCode(target) + ":" + m.getName();
        if (!caches.containsKey(cacheKey)) {
          try {
            if (!m.canAccess(target)) { try { m.setAccessible(true); } catch (Throwable t) { continue; } }
            long start = System.nanoTime();
            Object result = m.invoke(target);
            long cost = System.nanoTime() - start;
            if (cost > 100000 && result != null) {
              caches.put(cacheKey, result);
              caches.put(cacheKey + ":time", cost);
              count++;
            }
          } catch (Throwable ignore) {}
        }
      }
    }
  }
  private void optimizePureCalls(Object target) {
    Method[] ms; try { ms = target.getClass().getMethods(); } catch (Throwable ignore) { return; }
    for (Method m : ms) { 
      if (!looksPure(m) || (m.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue; 
      String key = "pure.method.ref:" + System.identityHashCode(target) + ":" + m.getName(); 
      caches.putIfAbsent(key, m); 
    }
  }
  private void optimizeFields(Object target) {
    Field[] fs; try { fs = target.getClass().getDeclaredFields(); } catch (Throwable ignore) { return; }
    for (Field f : fs) {
      if ((f.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue;
      boolean ok = f.canAccess(target);
      if (!ok) { try { ok = f.trySetAccessible(); } catch (Throwable ignore) { ok = false; } }
      if (!ok) continue;
      try { Object val = f.get(target); if (val instanceof java.util.Collection<?> || val instanceof java.util.Map<?,?>) optimizeCollections(val); } catch (Throwable ignore) {}
    }
  }
  private void extendMaps(Object target) {
    Object mc = caches.get("map.cache:" + System.identityHashCode(target));
    if (mc instanceof java.util.concurrent.ConcurrentHashMap<?,?>) {
      final java.util.concurrent.ConcurrentHashMap<?,?> localCache = (java.util.concurrent.ConcurrentHashMap<?,?>) mc;
      try {
        final Object tgt = target;
        final Method getM = tgt.getClass().getMethod("get", Object.class);
        java.util.function.Function<Object,Object> cachedGet = new java.util.function.Function<Object,Object>() {
          @Override
          public Object apply(Object k) {
            Object v = localCache.get(k); if (v != null) return v;
            try { Object r = getM.invoke(tgt, k); if (r != null) localCache.put(k, r); return r; } catch (Throwable t) { return null; }
          }
        };
        caches.put("map.fn.get:" + System.identityHashCode(target), cachedGet);
      } catch (Throwable ignore) {}
    }
  }
  private void optimizeReflectiveCalls(Object target) {
    if (!rate(target, "reflective", 5000)) return;
    try {
      java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
      Method[] methods = target.getClass().getDeclaredMethods();
      int count = 0;
      for (Method m : methods) {
        if (count >= 3) break;
        if (looksPure(m) && m.getParameterCount() == 0 && (m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
          String handleKey = "handle:" + System.identityHashCode(target) + ":" + m.getName();
          if (!caches.containsKey(handleKey)) {
            try {
              if (!m.canAccess(target)) { try { m.setAccessible(true); } catch (Throwable t) { continue; } }
              java.lang.invoke.MethodHandle handle = lookup.unreflect(m);
              caches.put(handleKey, handle);
              count++;
            } catch (Throwable ignore) {}
          }
        }
      }
    } catch (Throwable ignore) {}
  }
  @Override
  public void apply(String category, Object target, java.util.Map<String,Object> context) {
    if (target == null) return;
    if (!rate(target, "touch", 2500)) return;
    
    try { optimizeCollections(target); } catch (Throwable ignore) {}
    try { optimizeGetters(target); } catch (Throwable ignore) {}
    try { optimizePureCalls(target); } catch (Throwable ignore) {}
    try { optimizeFields(target); } catch (Throwable ignore) {}
    try { extendMaps(target); } catch (Throwable ignore) {}
    try { optimizeReflectiveCalls(target); } catch (Throwable ignore) {}
  }
