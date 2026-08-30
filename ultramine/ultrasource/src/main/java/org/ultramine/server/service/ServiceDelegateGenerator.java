package org.ultramine.server.service;

import static org.objectweb.asm.Opcodes.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.ultramine.server.util.UnsafeUtil;

import org.ultramine.core.service.ServiceDelegate;
import sun.misc.Unsafe;

public class ServiceDelegateGenerator
{
	private static final Unsafe U = UnsafeUtil.getUnsafe();
	private static final String ServiceDelegate_INTERNAL_NAME = Type.getInternalName(ServiceDelegate.class);
	private static final String NotImplementedServiceProvider_INTERNAL_NAME = Type.getInternalName(NotResolvedServiceProvider.class);

	//Unsafe.defineAnonymousClass was removed in Java 15+; its replacement
	//Lookup.defineHiddenClass appeared in Java 15. Resolved reflectively so this
	//class compiles and runs on the Java 8 baseline.
	private static final Method PRIVATE_LOOKUP_IN;
	private static final Method DEFINE_HIDDEN_CLASS;
	private static final Object EMPTY_CLASS_OPTIONS;

	static
	{
		Method privateLookupIn = null;
		Method defineHiddenClass = null;
		Object emptyOptions = null;
		try
		{
			Class<?> optionCls = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
			privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
			defineHiddenClass = MethodHandles.Lookup.class.getMethod("defineHiddenClass", byte[].class, boolean.class, Array.newInstance(optionCls, 0).getClass());
			emptyOptions = Array.newInstance(optionCls, 0);
		}
		catch (Exception e)
		{
			//Java 8..14 - the Unsafe.defineAnonymousClass path is used instead
		}
		PRIVATE_LOOKUP_IN = privateLookupIn;
		DEFINE_HIDDEN_CLASS = defineHiddenClass;
		EMPTY_CLASS_OPTIONS = emptyOptions;
	}

	private static Class<?> defineClass(Class<?> base, byte[] bytes)
	{
		if(DEFINE_HIDDEN_CLASS == null)
			return U.defineAnonymousClass(base, bytes, null);
		try
		{
			Object lookup = PRIVATE_LOOKUP_IN.invoke(null, base, MethodHandles.lookup());
			Object defined = DEFINE_HIDDEN_CLASS.invoke(lookup, bytes, false, EMPTY_CLASS_OPTIONS);
			return ((MethodHandles.Lookup) defined).lookupClass();
		}
		catch (InvocationTargetException e)
		{
			throw new RuntimeException("Failed to define service class near " + base.getName(), e.getCause());
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("Failed to define service class near " + base.getName(), e);
		}
	}

	//A hidden class must be named in the same package as the lookup (base) class
	private static String inPackageOf(Class<?> base, String name)
	{
		if(name.indexOf('.') >= 0)
			return name;
		String baseName = base.getName();
		int ind = baseName.lastIndexOf('.');
		return ind < 0 ? name : baseName.substring(0, ind + 1) + name;
	}

	@SuppressWarnings("unchecked")
	public static <T> Class<ServiceDelegate<T>> makeServiceDelegate(Class<?> base, String name, Class<T> iface)
	{
		return (Class<ServiceDelegate<T>>) defineClass(base, makeServiceDelegate(inPackageOf(base, name), iface));
	}

	public static byte[] makeServiceDelegate(String name, Class<?> iface)
	{
		if(!iface.isInterface())
			throw new IllegalArgumentException("iface should be an interface");

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

		String thisClassInternalName = name.replace('.',  '/');
		String ifaceInternalName = Type.getInternalName(iface);
		String ifaceDesc = Type.getDescriptor(iface);

		cw.visit(V1_5, ACC_PUBLIC | ACC_SUPER, thisClassInternalName, null, "java/lang/Object", new String[]{ ifaceInternalName, ServiceDelegate_INTERNAL_NAME });
		cw.visitSource(".dynamic", null);

		{
			cw.visitField(ACC_PUBLIC, "instance", ifaceDesc, null, null).visitEnd();
		}

		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "setProvider", "(Ljava/lang/Object;)V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ALOAD, 1);
			mv.visitTypeInsn(CHECKCAST, ifaceInternalName);
			mv.visitFieldInsn(PUTFIELD, thisClassInternalName, "instance", ifaceDesc);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getProvider", "()Ljava/lang/Object;", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, thisClassInternalName, "instance", ifaceDesc);
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		for(Method method : iface.getDeclaredMethods())
		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method.getName(), Type.getMethodDescriptor(method), null, null);
			mv.visitCode();

			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, thisClassInternalName, "instance", ifaceDesc);
			int argCounter = 1;
			for(Parameter par : method.getParameters())
			{
				int insn = loadInsnForType(par.getType());
				mv.visitVarInsn(insn, argCounter);
				argCounter += insn == LLOAD || insn == DLOAD ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, ifaceInternalName, method.getName(), Type.getMethodDescriptor(method), true);

			mv.visitInsn(returnInsnForType(method.getReturnType()));
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	@SuppressWarnings("unchecked")
	public static <T> Class<T> makeNotResolvedServiceProvider(Class<?> base, String name, Class<T> iface)
	{
		return (Class<T>) defineClass(base, makeNotResolvedServiceProvider(inPackageOf(base, name), iface));
	}

	public static byte[] makeNotResolvedServiceProvider(String name, Class<?> iface)
	{
		if(!iface.isInterface())
			throw new IllegalArgumentException("iface should be an interface");

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

		String thisClassInternalName = name.replace('.',  '/');
		String ifaceInternalName = Type.getInternalName(iface);
		String ifaceDesc = Type.getDescriptor(iface);

		cw.visit(V1_5, ACC_PUBLIC | ACC_SUPER, thisClassInternalName, null, NotImplementedServiceProvider_INTERNAL_NAME,
				new String[]{ ifaceInternalName});
		cw.visitSource(".dynamic", null);

		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, NotImplementedServiceProvider_INTERNAL_NAME, "<init>", "()V", false);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		for(Method method : iface.getDeclaredMethods())
		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method.getName(), Type.getMethodDescriptor(method), null, null);
			mv.visitCode();

			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKEVIRTUAL, thisClassInternalName, "resolveProvider", "()Ljava/lang/Object;", false);
			int argCounter = 1;
			for(Parameter par : method.getParameters())
			{
				int insn = loadInsnForType(par.getType());
				mv.visitVarInsn(insn, argCounter);
				argCounter += insn == LLOAD || insn == DLOAD ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, ifaceInternalName, method.getName(), Type.getMethodDescriptor(method), true);

			mv.visitInsn(returnInsnForType(method.getReturnType()));
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static int loadInsnForType(Class<?> cls)
	{
		if(cls == boolean.class || cls == byte.class || cls == short.class || cls == int.class) return ILOAD;
		if(cls == long.class) return LLOAD;
		if(cls == float.class) return FLOAD;
		if(cls == double.class) return DLOAD;
		return ALOAD;
	}

	private static int returnInsnForType(Class<?> cls)
	{
		if(cls == boolean.class || cls == byte.class || cls == short.class || cls == int.class) return IRETURN;
		if(cls == long.class) return LRETURN;
		if(cls == float.class) return FRETURN;
		if(cls == double.class) return DRETURN;
		if(cls == void.class) return RETURN;
		return ARETURN;
	}
}
