package com.shatyuka.zhiliao;

import android.content.SharedPreferences;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.shatyuka.zhiliao.xposed.XposedBridge;

/** Runtime fallback for targets whose obfuscated names move between Zhihu releases. */
public final class DexResolver {
    private static final String CACHE_PREFIX = "dexkit_resolver_v1_";

    private static DexKitBridge bridge;
    private static ClassLoader classLoader;
    private static SharedPreferences preferences;
    private static String versionPrefix;

    private DexResolver() {
    }

    public static synchronized void open(String apkPath, ClassLoader loader,
                                         SharedPreferences prefs, int versionCode) {
        close();
        classLoader = loader;
        preferences = prefs;
        File apk = apkPath == null ? null : new File(apkPath);
        long apkStamp = apk != null && apk.isFile()
                ? apk.length() ^ apk.lastModified() : 0;
        versionPrefix = CACHE_PREFIX + versionCode + "_"
                + CompatibilityRegistry.getRevision() + "_" + apkStamp + "_";
        if (apkPath == null || apkPath.isEmpty())
            return;
        try {
            System.loadLibrary("dexkit");
            bridge = DexKitBridge.create(apkPath);
        } catch (Throwable throwable) {
            bridge = null;
            XposedBridge.log("[Zhiliao] DexKit unavailable: " + throwable);
        }
    }

    public static synchronized void close() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
        }
        bridge = null;
        classLoader = null;
        preferences = null;
        versionPrefix = null;
    }

    public static synchronized Class<?> findClassBySuper(String key, String searchPackage,
                                                          Class<?> superClass) {
        Class<?> cached = loadCachedClass(key);
        if (cached != null && cached.getSuperclass() == superClass)
            return cached;
        if (bridge == null || classLoader == null)
            return null;
        try {
            List<ClassData> result = bridge.findClass(FindClass.create()
                    .searchPackages(searchPackage)
                    .matcher(ClassMatcher.create().superClass(superClass.getName())));
            for (ClassData data : result) {
                Class<?> candidate = data.getInstance(classLoader);
                if (candidate.getSuperclass() == superClass) {
                    save(key, candidate.getName());
                    return candidate;
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit class query " + key + " failed: " + throwable);
        }
        return null;
    }

    public static synchronized Method findMethod(String key, String searchPackage,
                                                  Class<?> ownerType, String methodName,
                                                  Class<?> returnType, Class<?>... parameterTypes) {
        Method cached = loadCachedMethod(key, ownerType, returnType, parameterTypes);
        if (cached != null)
            return cached;
        if (bridge == null || classLoader == null)
            return null;
        try {
            String[] parameterTypeNames = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++)
                parameterTypeNames[i] = parameterTypes[i].getName();
            List<MethodData> result = bridge.findMethod(FindMethod.create()
                    .searchPackages(searchPackage)
                    .matcher(MethodMatcher.create()
                            .name(methodName)
                            .returnType(returnType.getName())
                            .paramTypes(parameterTypeNames)));
            for (MethodData data : result) {
                Method candidate = data.getMethodInstance(classLoader);
                if ((ownerType == null || ownerType.isAssignableFrom(candidate.getDeclaringClass()))
                        && candidate.getReturnType() == returnType) {
                    save(key, candidate.getDeclaringClass().getName() + "#" + candidate.getName());
                    return candidate;
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit method query " + key + " failed: " + throwable);
        }
        return null;
    }

    public static synchronized Class<?> findClassByMethodName(String key, String searchPackage,
                                                               String methodName) {
        Class<?> cached = loadCachedClass(key);
        if (cached != null)
            return cached;
        if (bridge == null || classLoader == null)
            return null;
        try {
            List<MethodData> result = bridge.findMethod(FindMethod.create()
                    .searchPackages(searchPackage)
                    .matcher(MethodMatcher.create().name(methodName)));
            for (MethodData data : result) {
                Method method = data.getMethodInstance(classLoader);
                Class<?> candidate = method.getDeclaringClass();
                save(key, candidate.getName());
                return candidate;
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit method-owner query " + key + " failed: " + throwable);
        }
        return null;
    }

    /** Finds every method matching a stable signature when the owner name is obfuscated. */
    public static synchronized List<Method> findMethods(String key, String searchPackage,
                                                         String methodName, Class<?> returnType,
                                                         Class<?>... parameterTypes) {
        List<Method> methods = loadCachedMethods(key, methodName, returnType, parameterTypes);
        if (!methods.isEmpty())
            return methods;
        if (bridge == null || classLoader == null)
            return methods;
        try {
            String[] parameterTypeNames = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++)
                parameterTypeNames[i] = parameterTypes[i].getName();
            List<MethodData> result = bridge.findMethod(FindMethod.create()
                    .searchPackages(searchPackage)
                    .matcher(MethodMatcher.create()
                            .name(methodName)
                            .returnType(returnType.getName())
                            .paramTypes(parameterTypeNames)));
            for (MethodData data : result) {
                try {
                    Method candidate = data.getMethodInstance(classLoader);
                    if (candidate.getReturnType() == returnType)
                        methods.add(candidate);
                } catch (Throwable ignored) {
                }
            }
            if (!methods.isEmpty()) {
                StringBuilder cached = new StringBuilder();
                for (Method method : methods) {
                    if (cached.length() > 0)
                        cached.append(';');
                    cached.append(method.getDeclaringClass().getName()).append('#')
                            .append(method.getName());
                }
                save(key, cached.toString());
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit methods query " + key + " failed: " + throwable);
        }
        return methods;
    }

    /** Resolves a renamed method by stable calls in its implementation and caches it per host version. */
    public static synchronized Method findMethodByInvokes(String key, String searchPackage,
                                                           String ownerClassName, int paramCount,
                                                           String... invokedDescriptors) {
        Method cached = loadCachedLooseMethod(key, ownerClassName, paramCount);
        if (cached != null)
            return cached;
        if (bridge == null || classLoader == null)
            return null;
        try {
            MethodMatcher matcher = MethodMatcher.create()
                    .declaredClass(ownerClassName)
                    .returnType("void")
                    .paramCount(paramCount);
            for (String descriptor : invokedDescriptors)
                matcher.addInvoke(descriptor);
            List<MethodData> result = bridge.findMethod(FindMethod.create()
                    .searchPackages(searchPackage)
                    .matcher(matcher));
            for (MethodData data : result) {
                try {
                    Method candidate = data.getMethodInstance(classLoader);
                    if (ownerClassName.equals(candidate.getDeclaringClass().getName())
                            && candidate.getReturnType() == void.class
                            && candidate.getParameterCount() == paramCount) {
                        save(key, ownerClassName + "#" + candidate.getName());
                        return candidate;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit invoke query " + key + " failed: " + throwable);
        }
        return null;
    }

    /** Resolves one owner class using a validated cloud rule; ambiguity fails closed. */
    public static synchronized Class<?> findClassByRule(String ruleKey) {
        CompatibilityRegistry.DexRule rule = CompatibilityRegistry.getDexRule(ruleKey);
        if (rule == null || !"ownerClass".equals(rule.result) || classLoader == null)
            return null;
        String cacheKey = "cloud_class_" + ruleKey;
        Class<?> cached = loadCachedClass(cacheKey);
        if (cached != null)
            return cached;
        if (bridge == null)
            return null;
        List<Method> methods = queryRule(ruleKey, rule);
        Map<String, Class<?>> owners = new LinkedHashMap<>();
        for (Method method : methods)
            owners.put(method.getDeclaringClass().getName(), method.getDeclaringClass());
        if (owners.size() < rule.minCandidates || owners.size() > rule.maxCandidates
                || owners.size() != 1) {
            logRejectedRule(ruleKey, owners.size(), rule);
            return null;
        }
        Class<?> result = owners.values().iterator().next();
        save(cacheKey, result.getName());
        return result;
    }

    /** Resolves methods from the allow-listed cloud matcher properties only. */
    public static synchronized List<Method> findMethodsByRule(String ruleKey) {
        CompatibilityRegistry.DexRule rule = CompatibilityRegistry.getDexRule(ruleKey);
        List<Method> empty = new ArrayList<>();
        if (rule == null || !"method".equals(rule.result) || classLoader == null)
            return empty;
        String cacheKey = "cloud_methods_" + ruleKey;
        List<Method> cached = loadCachedRuleMethods(cacheKey, rule);
        if (!cached.isEmpty())
            return cached;
        if (bridge == null)
            return empty;
        List<Method> methods = queryRule(ruleKey, rule);
        if (methods.size() < rule.minCandidates || methods.size() > rule.maxCandidates) {
            logRejectedRule(ruleKey, methods.size(), rule);
            return empty;
        }
        saveRuleMethods(cacheKey, methods);
        return methods;
    }

    /** Resolves one field from a field rule; ambiguous results fail closed. */
    public static synchronized Field findFieldByRule(String ruleKey) {
        CompatibilityRegistry.DexRule rule = CompatibilityRegistry.getDexRule(ruleKey);
        if (rule == null || !"field".equals(rule.result) || classLoader == null)
            return null;
        String cacheKey = "cloud_field_" + ruleKey;
        Field cached = loadCachedField(cacheKey, rule);
        if (cached != null)
            return cached;
        if (bridge == null)
            return null;
        List<Field> fields = queryFieldRule(ruleKey, rule);
        if (fields.size() < rule.minCandidates || fields.size() > rule.maxCandidates
                || fields.size() != 1) {
            logRejectedRule(ruleKey, fields.size(), rule);
            return null;
        }
        Field result = fields.get(0);
        result.setAccessible(true);
        save(cacheKey, result.getDeclaringClass().getName() + "#" + result.getName());
        return result;
    }

    /** Resolves the declaring class of a field rule; ambiguous owners fail closed. */
    public static synchronized Class<?> findFieldOwnerClassByRule(String ruleKey) {
        CompatibilityRegistry.DexRule rule = CompatibilityRegistry.getDexRule(ruleKey);
        if (rule == null || !"fieldOwnerClass".equals(rule.result) || classLoader == null)
            return null;
        String cacheKey = "cloud_field_owner_" + ruleKey;
        Field cached = loadCachedField(cacheKey, rule);
        if (cached != null)
            return cached.getDeclaringClass();
        if (bridge == null)
            return null;
        List<Field> fields = queryFieldRule(ruleKey, rule);
        Map<String, Class<?>> owners = new LinkedHashMap<>();
        for (Field field : fields)
            owners.put(field.getDeclaringClass().getName(), field.getDeclaringClass());
        if (owners.size() < rule.minCandidates || owners.size() > rule.maxCandidates
                || owners.size() != 1) {
            logRejectedRule(ruleKey, owners.size(), rule);
            return null;
        }
        Class<?> result = owners.values().iterator().next();
        for (Field field : fields) {
            if (field.getDeclaringClass() == result) {
                save(cacheKey, result.getName() + "#" + field.getName());
                break;
            }
        }
        return result;
    }

    private static Field loadCachedField(String key, CompatibilityRegistry.DexRule rule) {
        String value = load(key);
        if (value == null || classLoader == null)
            return null;
        int separator = value.lastIndexOf('#');
        if (separator <= 0)
            return null;
        try {
            Field field = classLoader.loadClass(value.substring(0, separator))
                    .getDeclaredField(value.substring(separator + 1));
            if (!matchesFieldRule(field, rule))
                return null;
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Field> queryFieldRule(String ruleKey,
                                               CompatibilityRegistry.DexRule rule) {
        Map<String, Field> fields = new LinkedHashMap<>();
        try {
            FieldMatcher matcher = FieldMatcher.create();
            if (rule.fieldNames.size() == 1) {
                matcher.name(rule.fieldNames.get(0));
            } else if (rule.fieldNames.size() > 1) {
                List<FieldMatcher> names = new ArrayList<>();
                for (String name : rule.fieldNames)
                    names.add(FieldMatcher.create().name(name));
                matcher.anyOf(names);
            }
            if (!rule.fieldType.isEmpty())
                matcher.type(rule.fieldType);
            List<FieldData> found = bridge.findField(FindField.create()
                    .searchPackages(rule.searchPackages).matcher(matcher));
            for (FieldData data : found) {
                try {
                    Field candidate = data.getFieldInstance(classLoader);
                    if (!matchesFieldRule(candidate, rule))
                        continue;
                    fields.put(candidate.getDeclaringClass().getName() + "#"
                            + candidate.getName(), candidate);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit cloud field rule " + ruleKey
                    + " failed: " + throwable);
        }
        return new ArrayList<>(fields.values());
    }

    private static boolean matchesFieldRule(Field field, CompatibilityRegistry.DexRule rule) {
        if (!rule.fieldNames.isEmpty() && !rule.fieldNames.contains(field.getName()))
            return false;
        if (!rule.fieldType.isEmpty() && !rule.fieldType.equals(field.getType().getName()))
            return false;
        String owner = field.getDeclaringClass().getName();
        for (String searchPackage : rule.searchPackages) {
            if (owner.equals(searchPackage) || owner.startsWith(searchPackage + "."))
                return true;
        }
        return false;
    }
    private static List<Method> queryRule(String ruleKey, CompatibilityRegistry.DexRule rule) {
        Map<String, Method> methods = new LinkedHashMap<>();
        if (bridge == null || classLoader == null)
            return new ArrayList<>();
        try {
            MethodMatcher matcher = MethodMatcher.create();
            if (rule.methodNames.size() == 1) {
                matcher.name(rule.methodNames.get(0));
            } else if (rule.methodNames.size() > 1) {
                List<MethodMatcher> names = new ArrayList<>();
                for (String name : rule.methodNames)
                    names.add(MethodMatcher.create().name(name));
                matcher.anyOf(names);
            }
            if (!rule.returnType.isEmpty())
                matcher.returnType(rule.returnType);
            if (rule.hasParamTypes)
                matcher.paramTypes(rule.paramTypes);
            else if (rule.paramCount >= 0)
                matcher.paramCount(rule.paramCount);
            if (!rule.usingStrings.isEmpty())
                matcher.usingEqStrings(rule.usingStrings);
            for (String descriptor : rule.invokes)
                matcher.addInvoke(descriptor);
            List<MethodData> found = bridge.findMethod(FindMethod.create()
                    .searchPackages(rule.searchPackages).matcher(matcher));
            for (MethodData data : found) {
                try {
                    Method candidate = data.getMethodInstance(classLoader);
                    if (!matchesRule(candidate, rule))
                        continue;
                    String signature = candidate.getDeclaringClass().getName() + "#"
                            + candidate.getName() + parameterKey(candidate.getParameterTypes());
                    methods.put(signature, candidate);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("[Zhiliao] DexKit cloud rule " + ruleKey + " failed: " + throwable);
        }
        return new ArrayList<>(methods.values());
    }

    private static boolean matchesRule(Method method, CompatibilityRegistry.DexRule rule) {
        if (!rule.methodNames.isEmpty() && !rule.methodNames.contains(method.getName()))
            return false;
        if (!rule.returnType.isEmpty()
                && !rule.returnType.equals(method.getReturnType().getName()))
            return false;
        if (rule.hasParamTypes) {
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != rule.paramTypes.size())
                return false;
            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].getName().equals(rule.paramTypes.get(i)))
                    return false;
            }
        } else if (rule.paramCount >= 0 && rule.paramCount != method.getParameterCount()) {
            return false;
        }
        String owner = method.getDeclaringClass().getName();
        for (String searchPackage : rule.searchPackages) {
            if (owner.equals(searchPackage) || owner.startsWith(searchPackage + "."))
                return true;
        }
        return false;
    }

    private static List<Method> loadCachedRuleMethods(String key,
                                                       CompatibilityRegistry.DexRule rule) {
        List<Method> result = new ArrayList<>();
        String value = load(key);
        if (value == null || classLoader == null)
            return result;
        try {
            for (String item : value.split(";")) {
                String[] parts = item.split("#", -1);
                if (parts.length != 3)
                    return new ArrayList<>();
                String[] names = parts[2].isEmpty() ? new String[0] : parts[2].split(",");
                Class<?>[] parameterTypes = new Class<?>[names.length];
                for (int i = 0; i < names.length; i++)
                    parameterTypes[i] = loadType(names[i]);
                Method method = classLoader.loadClass(parts[0])
                        .getDeclaredMethod(parts[1], parameterTypes);
                if (!matchesRule(method, rule))
                    return new ArrayList<>();
                method.setAccessible(true);
                result.add(method);
            }
            return result.size() >= rule.minCandidates && result.size() <= rule.maxCandidates
                    ? result : new ArrayList<>();
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static void saveRuleMethods(String key, List<Method> methods) {
        StringBuilder value = new StringBuilder();
        for (Method method : methods) {
            if (value.length() > 0)
                value.append(';');
            value.append(method.getDeclaringClass().getName()).append('#')
                    .append(method.getName()).append('#')
                    .append(parameterKey(method.getParameterTypes()));
        }
        save(key, value.toString());
    }

    private static String parameterKey(Class<?>[] parameterTypes) {
        StringBuilder value = new StringBuilder();
        for (Class<?> type : parameterTypes) {
            if (value.length() > 0)
                value.append(',');
            value.append(type.getName());
        }
        return value.toString();
    }

    private static Class<?> loadType(String name) throws ClassNotFoundException {
        switch (name) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            default: return Class.forName(name, false, classLoader);
        }
    }

    private static void logRejectedRule(String key, int count,
                                        CompatibilityRegistry.DexRule rule) {
        XposedBridge.log("[Zhiliao] DexKit cloud rule " + key + " rejected "
                + count + " candidates; expected " + rule.minCandidates + ".."
                + rule.maxCandidates);
    }
    private static Method loadCachedLooseMethod(String key, String ownerClassName,
                                                 int paramCount) {
        String value = load(key);
        if (value == null || classLoader == null)
            return null;
        int separator = value.lastIndexOf('#');
        if (separator <= 0 || !ownerClassName.equals(value.substring(0, separator)))
            return null;
        String methodName = value.substring(separator + 1);
        try {
            Class<?> owner = classLoader.loadClass(ownerClassName);
            for (Method method : owner.getDeclaredMethods()) {
                if (methodName.equals(method.getName()) && method.getReturnType() == void.class
                        && method.getParameterCount() == paramCount)
                    return method;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    private static List<Method> loadCachedMethods(String key, String methodName,
                                                   Class<?> returnType,
                                                   Class<?>[] parameterTypes) {
        List<Method> result = new ArrayList<>();
        String value = load(key);
        if (value == null || classLoader == null)
            return result;
        try {
            for (String item : value.split(";")) {
                int separator = item.lastIndexOf('#');
                if (separator <= 0 || !methodName.equals(item.substring(separator + 1)))
                    return new ArrayList<>();
                Class<?> owner = classLoader.loadClass(item.substring(0, separator));
                Method method = owner.getMethod(methodName, parameterTypes);
                if (method.getReturnType() != returnType)
                    return new ArrayList<>();
                result.add(method);
            }
            return result;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static Class<?> loadCachedClass(String key) {
        String value = load(key);
        if (value == null || value.indexOf('#') >= 0 || classLoader == null)
            return null;
        try {
            return classLoader.loadClass(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method loadCachedMethod(String key, Class<?> ownerType, Class<?> returnType,
                                           Class<?>[] parameterTypes) {
        String value = load(key);
        if (value == null || classLoader == null)
            return null;
        int separator = value.lastIndexOf('#');
        if (separator <= 0)
            return null;
        try {
            Class<?> owner = classLoader.loadClass(value.substring(0, separator));
            if (ownerType != null && !ownerType.isAssignableFrom(owner))
                return null;
            Method method = owner.getMethod(value.substring(separator + 1), parameterTypes);
            return method.getReturnType() == returnType ? method : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String load(String key) {
        return preferences == null || versionPrefix == null
                ? null : preferences.getString(versionPrefix + key, null);
    }

    private static void save(String key, String value) {
        if (preferences != null && versionPrefix != null)
            preferences.edit().putString(versionPrefix + key, value).apply();
    }
}
