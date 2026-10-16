package com.happyboy.tokay.asm.asm

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

abstract class GeckoLoaderVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {

    @Override
    ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new GeckoLoaderClassVisitor(Opcodes.ASM9, nextClassVisitor)
    }

    @Override
    boolean isInstrumentable(ClassData classData) {
        def name = classData.getClassName()
        if (name == null) return false
        return InstrumentationTargets.hasTargetsFor(InstrumentationTargets.GECKO_LOADER, name.replace('.', '/'))
    }
}

class GeckoLoaderClassVisitor extends ClassVisitor {
    GeckoLoaderClassVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor)
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        def mv = super.visitMethod(access, name, descriptor, signature, exceptions)

        // 匹配条件来自 InstrumentationTargets（**唯一数据源**），与构建期断言共用同一张表。
        def target = InstrumentationTargets.match(InstrumentationTargets.GECKO_LOADER, access, name, descriptor)
        if (target != null) {
            return target.createVisitor(api, mv, access, name, descriptor)
        }

        return mv
    }
}

class GeckoLoaderGetLibraryBaseReplacer extends AdviceAdapter {
    private static final Logger logger = Logging.getLogger('GeckoViewASM')
    private boolean isReplacing = false
    
    protected GeckoLoaderGetLibraryBaseReplacer(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor)
    }
    
    @Override
    void visitCode() {
        super.visitCode()
        
        // return getLibraryPath("mozglue")
        mv.visitLdcInsn('mozglue')
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'getLibraryPath', '(Ljava/lang/String;)Ljava/lang/String;', false)
        mv.visitInsn(Opcodes.ARETURN)
        
        isReplacing = true
        logger.lifecycle('[GeckoViewASM] 替换 GeckoLoader.getLibraryBase() 成功')
    }
    
    @Override
    void visitInsn(int opcode) {
        if (isReplacing) {
            return
        }
        super.visitInsn(opcode)
    }
    
    @Override
    void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if (isReplacing) {
            return
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

class GeckoLoaderGetLibraryPathReplacer extends AdviceAdapter {
    private static final Logger logger = Logging.getLogger('GeckoViewASM')
    private boolean isReplacing = false
    
    protected GeckoLoaderGetLibraryPathReplacer(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor)
    }
    
    @Override
    void visitCode() {
        super.visitCode()
        // 用 GeckoNativePathResolver 解析 ABI 目录
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            'org/mozilla/gecko/GeckoAppShell',
            'getApplicationContext',
            '()Landroid/content/Context;',
            false
        )
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            InstrumentedLibraryNames.internalName(InstrumentedLibraryNames.RESOLVER),
            'getAbiLibDir',
            '(Landroid/content/Context;)Ljava/lang/String;',
            false
        )
        mv.visitInsn(Opcodes.ARETURN)
        
        isReplacing = true
        logger.lifecycle('[GeckoViewASM] 替换 GeckoLoader.getLibraryPath(String) 完成')
    }
    
    @Override
    void visitInsn(int opcode) {
        if (isReplacing) {
            return
        }
        super.visitInsn(opcode)
    }
    
    @Override
    void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if (isReplacing) {
            return
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

class GeckoLoaderDoLoadLibraryReplacer extends AdviceAdapter {
    private static final Logger logger = Logging.getLogger('GeckoViewASM')
    private boolean isReplacing = false
    
    protected GeckoLoaderDoLoadLibraryReplacer(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor)
    }
    
    @Override
    void visitCode() {
        super.visitCode()
        
        // try { System.loadLibrary(lib); return null; } catch (Throwable e) { ... }
        def tryStart = new org.objectweb.asm.Label()
        def tryEnd = new org.objectweb.asm.Label()
        def catchStart = new org.objectweb.asm.Label()
        def catchEnd = new org.objectweb.asm.Label()
        mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, 'java/lang/Throwable')
        mv.visitLabel(tryStart)
        // System.loadLibrary(lib)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'java/lang/System', 'loadLibrary', '(Ljava/lang/String;)V', false)
        // return null
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(tryEnd)
        // catch (Throwable e)
        mv.visitLabel(catchStart)
        // store e in local
        def eLocal = newLocal(org.objectweb.asm.Type.getType('Ljava/lang/Throwable;'))
        mv.visitVarInsn(Opcodes.ASTORE, eLocal)
        
        // String libPath = getLibraryPath("")
        def libPathLocal = newLocal(org.objectweb.asm.Type.getType('Ljava/lang/String;'))
        mv.visitLdcInsn('')
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'getLibraryPath', '(Ljava/lang/String;)Ljava/lang/String;', false)
        mv.visitVarInsn(Opcodes.ASTORE, libPathLocal)
        
        // File libFile = new File(libPath, "lib" + lib + ".so")
        def libFileLocal = newLocal(org.objectweb.asm.Type.getType('Ljava/io/File;'))
        mv.visitTypeInsn(Opcodes.NEW, 'java/io/File')
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, libPathLocal)
        // build file name: "lib" + lib + ".so"
        mv.visitTypeInsn(Opcodes.NEW, 'java/lang/StringBuilder')
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn('lib')
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/lang/StringBuilder', '<init>', '(Ljava/lang/String;)V', false)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitLdcInsn('.so')
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'toString', '()Ljava/lang/String;', false)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/io/File', '<init>', '(Ljava/lang/String;Ljava/lang/String;)V', false)
        mv.visitVarInsn(Opcodes.ASTORE, libFileLocal)
        
        // if (libFile.exists()) {
        def labelExistsFalse = new org.objectweb.asm.Label()
        mv.visitVarInsn(Opcodes.ALOAD, libFileLocal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/io/File', 'exists', '()Z', false)
        mv.visitJumpInsn(Opcodes.IFEQ, labelExistsFalse)
        // if (attemptLoad(libFile.getAbsolutePath())) return null;
        loadLocal(libFileLocal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/io/File', 'getAbsolutePath', '()Ljava/lang/String;', false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'attemptLoad', '(Ljava/lang/String;)Z', false)
        def labelAfterAttempt = new org.objectweb.asm.Label()
        mv.visitJumpInsn(Opcodes.IFEQ, labelAfterAttempt)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(labelAfterAttempt)
        // throw new RuntimeException("Library exists but couldn't load." + "Path: " + libPath + " lib: " + lib, e);
        mv.visitTypeInsn(Opcodes.NEW, 'java/lang/RuntimeException')
        mv.visitInsn(Opcodes.DUP)
        // message builder
        mv.visitTypeInsn(Opcodes.NEW, 'java/lang/StringBuilder')
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn("Library exists but couldn't load.Path: ")
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/lang/StringBuilder', '<init>', '(Ljava/lang/String;)V', false)
        mv.visitVarInsn(Opcodes.ALOAD, libPathLocal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitLdcInsn(' lib: ')
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'toString', '()Ljava/lang/String;', false)
        // ctor(message, cause)
        mv.visitVarInsn(Opcodes.ALOAD, eLocal)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/lang/RuntimeException', '<init>', '(Ljava/lang/String;Ljava/lang/Throwable;)V', false)
        mv.visitInsn(Opcodes.ATHROW)
        
        // } else { throw new RuntimeException("Library doesn't exist when it should." + "Path: " + libPath + " lib: " + lib, e); }
        mv.visitLabel(labelExistsFalse)
        mv.visitTypeInsn(Opcodes.NEW, 'java/lang/RuntimeException')
        mv.visitInsn(Opcodes.DUP)
        // message builder
        mv.visitTypeInsn(Opcodes.NEW, 'java/lang/StringBuilder')
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn("Library doesn't exist when it should.Path: ")
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/lang/StringBuilder', '<init>', '(Ljava/lang/String;)V', false)
        mv.visitVarInsn(Opcodes.ALOAD, libPathLocal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitLdcInsn(' lib: ')
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'toString', '()Ljava/lang/String;', false)
        // ctor(message, cause)
        mv.visitVarInsn(Opcodes.ALOAD, eLocal)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/lang/RuntimeException', '<init>', '(Ljava/lang/String;Ljava/lang/Throwable;)V', false)
        mv.visitInsn(Opcodes.ATHROW)
        
        mv.visitLabel(catchEnd)
        
        isReplacing = true
        logger.lifecycle('[GeckoViewASM] 替换 GeckoLoader.doLoadLibrary(Context,String) 完成')
    }
    
    @Override
    void visitInsn(int opcode) {
        if (isReplacing) {
            return
        }
        super.visitInsn(opcode)
    }
    
    @Override
    void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if (isReplacing) {
            return
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

class GeckoLoaderAttemptLoadVisitor extends AdviceAdapter {
    private static final Logger logger = Logging.getLogger('GeckoViewASM')
    private boolean isReplacing = false

    protected GeckoLoaderAttemptLoadVisitor(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor)
    }

    @Override
    void visitCode() {
        super.visitCode()

        // try { System.load(path); return true; } catch (Throwable e) { Log.wtf(...); return false; }
        def tryStart = new org.objectweb.asm.Label()
        def tryEnd = new org.objectweb.asm.Label()
        def catchStart = new org.objectweb.asm.Label()
        def catchEnd = new org.objectweb.asm.Label()
        mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, 'java/lang/Throwable')
        mv.visitLabel(tryStart)
        // System.load(path)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'java/lang/System', 'load', '(Ljava/lang/String;)V', false)
        // return true
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(tryEnd)
        // catch (Throwable e)
        mv.visitLabel(catchStart)
        def eLocal = newLocal(org.objectweb.asm.Type.getType('Ljava/lang/Throwable;'))
        mv.visitVarInsn(Opcodes.ASTORE, eLocal)
        // Log.wtf(LOGTAG, "Couldn't load " + path + ": " + e)
        mv.visitLdcInsn('GeckoLoader')
        mv.visitTypeInsn(Opcodes.NEW, 'java/lang/StringBuilder')
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn("Couldn't load ")
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 'java/lang/StringBuilder', '<init>', '(Ljava/lang/String;)V', false)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitLdcInsn(': ')
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/String;)Ljava/lang/StringBuilder;', false)
        mv.visitVarInsn(Opcodes.ALOAD, eLocal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'append', '(Ljava/lang/Object;)Ljava/lang/StringBuilder;', false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/StringBuilder', 'toString', '()Ljava/lang/String;', false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'android/util/Log', 'wtf', '(Ljava/lang/String;Ljava/lang/String;)I', false)
        mv.visitInsn(Opcodes.POP)
        // return false
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(catchEnd)

        isReplacing = true
        logger.lifecycle('[GeckoViewASM] 替换 GeckoLoader.attemptLoad(String) 完成')
    }

    @Override
    void visitInsn(int opcode) {
        if (isReplacing) {
            return
        }
        super.visitInsn(opcode)
    }
}

class GeckoLoaderLoadGeckoLibsReplacer extends AdviceAdapter {
    private static final Logger logger = Logging.getLogger('GeckoViewASM')
    private boolean isReplacing = false

    protected GeckoLoaderLoadGeckoLibsReplacer(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor)
    }

    @Override
    void visitCode() {
        super.visitCode()
        
        // loadLibsSetupLocked(context);
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'loadLibsSetupLocked', '(Landroid/content/Context;)V', false)
        
        // doLoadLibrary(context, "lgpllibs");
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitLdcInsn('lgpllibs')
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'doLoadLibrary', '(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Throwable;', false)
        mv.visitInsn(Opcodes.POP) // 忽略返回值
        
        // doLoadLibrary(context, "gkcodecs");
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitLdcInsn('gkcodecs')
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'doLoadLibrary', '(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Throwable;', false)
        mv.visitInsn(Opcodes.POP) // 忽略返回值
        
        // loadGeckoLibsNative();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, 'org/mozilla/gecko/mozglue/GeckoLoader', 'loadGeckoLibsNative', '()V', false)
        
        // return (void)
        mv.visitInsn(Opcodes.RETURN)
        
        isReplacing = true
        logger.lifecycle('[GeckoViewASM] 替换 GeckoLoader.loadGeckoLibs(Context) 完成')
    }
    
    @Override
    void visitInsn(int opcode) {
        if (isReplacing) {
            return
        }
        super.visitInsn(opcode)
    }
    
    @Override
    void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if (isReplacing) {
            return
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
