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

abstract class GeckoThreadVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {

    @Override
    ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new GeckoThreadClassVisitor(Opcodes.ASM9, nextClassVisitor)
    }

    @Override
    boolean isInstrumentable(ClassData classData) {
        def name = classData.getClassName()
        if (name == null) return false
        return InstrumentationTargets.hasTargetsFor(InstrumentationTargets.GECKO_THREAD, name.replace('.', '/'))
    }
}

class GeckoThreadClassVisitor extends ClassVisitor {
    GeckoThreadClassVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor)
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        def mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        // 匹配条件来自 InstrumentationTargets（**唯一数据源**），与构建期断言共用同一张表
        def target = InstrumentationTargets.match(InstrumentationTargets.GECKO_THREAD, access, name, descriptor)
        if (target != null) {
            return target.createVisitor(api, mv, access, name, descriptor)
        }
        return mv
    }
}

class GeckoThreadGetMainProcessArgsVisitor extends AdviceAdapter {

    private boolean skipNextArgsAdd = false
    private boolean skipNextPop = false
    private boolean inReplacement = false
    private static final Logger logger = Logging.getLogger('GeckoViewASM')
    private boolean didLog = false

    protected GeckoThreadGetMainProcessArgsVisitor(int api, MethodVisitor methodVisitor, int access, String name, String descriptor) {
        super(api, methodVisitor, access, name, descriptor)
    }

    @Override
    void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if (inReplacement) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }
        // GeckoView 把 getPackageResourcePath() 直接塞进 -greomni，这里替换为 OTA 内核包里的 res/base.apk
        if (owner == 'android/content/Context' && name == 'getPackageResourcePath' && descriptor == '()Ljava/lang/String;') {
            inReplacement = true
            def tmpContext = newLocal(org.objectweb.asm.Type.getType('Landroid/content/Context;'))
            def tmpArgs = newLocal(org.objectweb.asm.Type.getType('Ljava/util/ArrayList;'))
            storeLocal(tmpContext)
            storeLocal(tmpArgs)

            loadLocal(tmpArgs)
            loadLocal(tmpContext)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                InstrumentedLibraryNames.internalName(InstrumentedLibraryNames.RESOLVER),
                'getMainProcessApkPath',
                '(Landroid/content/Context;)Ljava/lang/String;',
                false
            )
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/util/ArrayList', 'add', '(Ljava/lang/Object;)Z', false)
            visitInsn(Opcodes.POP)

            skipNextArgsAdd = true
            skipNextPop = true
            inReplacement = false
            if (!didLog) {
                logger.lifecycle('[GeckoViewASM] 修改 GeckoThread.getMainProcessArgs() 成功')
                didLog = true
            }
            return
        }

        if (skipNextArgsAdd && owner == 'java/util/ArrayList' && name == 'add' && descriptor == '(Ljava/lang/Object;)Z') {
            skipNextArgsAdd = false
            return
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    @Override
    void visitInsn(int opcode) {
        if (skipNextPop && opcode == Opcodes.POP) {
            skipNextPop = false
            return
        }
        super.visitInsn(opcode)
    }
}
