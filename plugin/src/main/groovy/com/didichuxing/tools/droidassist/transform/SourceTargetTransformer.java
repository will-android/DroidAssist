package com.didichuxing.tools.droidassist.transform;

import com.didichuxing.tools.droidassist.ex.DroidAssistBadStatementException;
import com.didichuxing.tools.droidassist.spec.SourceSpec;
import com.didichuxing.tools.droidassist.util.ClassUtils;
import com.didichuxing.tools.droidassist.util.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMember;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.ClassFile;
import javassist.bytecode.Descriptor;
import javassist.bytecode.MethodInfo;
import javassist.expr.FieldAccess;
import javassist.expr.MethodCall;
import javassist.expr.NewExpr;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "RedundantThrows"})
public abstract class SourceTargetTransformer extends Transformer {
    private String target;
    private String source;
    private SourceSpec sourceSpec;

    private CtClass sourceClass;
    private String sourceDeclaringClassName;
    private CtMember sourceMember;
    private CtClass sourceReturnType;

    public SourceTargetTransformer setSource(String source, String kind, boolean extend) {
        this.source = source;
        sourceSpec = SourceSpec.fromString(source, kind, extend);
        return this;
    }

    public SourceTargetTransformer setTarget(String target) {
        target = target.trim();
        this.target = target;
        return this;
    }

    public String getSource() {
        return source;
    }

    private String getSourceDeclaringClassName() {
        if (sourceDeclaringClassName == null) {
            sourceDeclaringClassName = sourceSpec.getDeclaringClassName();
        }
        return sourceDeclaringClassName;
    }

    private CtClass getSourceClass() throws NotFoundException {
        if (sourceClass == null) {
            sourceClass = classPool.getCtClass(getSourceDeclaringClassName());
        }
        return sourceClass;
    }

    private CtMember getSourceMember() throws NotFoundException {
        if (sourceMember == null) {
            CtClass sourceClass = getSourceClass();
            String name = sourceSpec.getName();
            String signature = sourceSpec.getSignature();
            if (sourceSpec.getKind() == SourceSpec.Kind.METHOD) {
                sourceMember = ClassUtils.getDeclaredMethod(sourceClass, name, signature);
            }
        }
        return sourceMember;
    }

    protected CtClass getSourceReturnType() throws NotFoundException {
        if (sourceReturnType == null) {
            sourceReturnType = Descriptor.getReturnType(sourceSpec.getSignature(), classPool);
        }
        return sourceReturnType;
    }

    protected boolean isVoidSourceReturnType() throws NotFoundException {
        return Descriptor.getReturnType(sourceSpec.getSignature(), classPool) == CtClass.voidType;
    }

    boolean isMatchSourceClass(CtClass insnClass) throws NotFoundException {
        boolean match = false;
        Boolean anInterface = isInterface(insnClass);
        if (anInterface == null || anInterface) {
            return false;
        }
        if (sourceSpec.isExtend()) {
            CtClass sourceClass = getSourceClass();
            for (CtClass itf : tryGetInterfaces(insnClass)) {
                if (itf == sourceClass) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                match = insnClass.subclassOf(sourceClass);
            }
        } else {
            match = insnClass.getName().equals(getSourceDeclaringClassName());
        }
        return match;
    }

    protected boolean isMatchSourceClassName(String insnClass) {
        return insnClass.equals(getSourceDeclaringClassName());
    }

    protected boolean isMatchSourceMethod(
            CtClass insnClass,
            String name,
            String signature)
            throws NotFoundException {
        return isMatchSourceMethod(insnClass, true, name, signature);
    }

    @SuppressWarnings("ConstantConditions")
    protected boolean isMatchSourceMethod(
            CtClass insnClass,
            boolean checkClass,
            String name,
            String signature)
            throws NotFoundException {

        boolean match = true;
        do {
            if (!name.equals(sourceSpec.getName())
                    || !signature.equals(sourceSpec.getSignature())) {
                match = false;
                break;
            }

            if (checkClass) {
                boolean matchClass = false;
                if (sourceSpec.isExtend()) {
                    CtClass sourceClass = getSourceClass();
                    for (CtClass itf : tryGetInterfaces(insnClass)) {
                        if (itf == sourceClass) {
                            matchClass = true;
                            break;
                        }
                    }
                    if (!matchClass) {
                        matchClass = insnClass.subclassOf(sourceClass);
                    }
                } else {
                    matchClass = insnClass.getName().equals(getSourceDeclaringClassName());
                }
                if (!matchClass) {
                    match = false;
                    break;
                }
            }

            if (sourceSpec.getKind() == SourceSpec.Kind.METHOD) {
                CtMember member = getSourceMember();
                boolean visible = member.visibleFrom(insnClass);
                if (!visible) {
                    match = false;
                    break;
                }
            }

        } while (false);
        return match;
    }

    @SuppressWarnings("ConstantConditions")
    protected boolean isMatchConstructorSource(String classname, String signature) {
        return classname.equals(getSourceDeclaringClassName())
                && signature.equals(sourceSpec.getSignature());
    }

    @SuppressWarnings("ConstantConditions")
    protected boolean isMatchFieldSource(String classname, String signature, String fieldName) {
        return classname.equals(getSourceDeclaringClassName())
                && signature.equals(sourceSpec.getSignature())
                && fieldName.equals(sourceSpec.getName());
    }

    public String getTarget() {
        return target;
    }

    @Override
    public String getName() {
        return "ReplaceTransformer";
    }

    protected String getReplaceStatement(MethodCall methodCall, String statement) {
        int line = methodCall.getLineNumber();
        String name = methodCall.getMethodName();
        String className = methodCall.getClassName();
        String fileName = methodCall.getFileName();
        return getReplaceStatement(statement, line, name, className, fileName);
    }

    protected String getReplaceStatement(NewExpr expr, String statement) {
        int line = expr.getLineNumber();
        String name = "<init>";
        String className = expr.getClassName();
        String fileName = expr.getFileName();
        return getReplaceStatement(statement, line, name, className, fileName);
    }

    protected String getReplaceStatement(FieldAccess fieldAccess, String statement) {
        int line = fieldAccess.getLineNumber();
        String name = fieldAccess.getFieldName();
        String className = fieldAccess.getClassName();
        String fileName = fieldAccess.getFileName();
        return getReplaceStatement(statement, line, name, className, fileName);
    }

    protected String getReplaceStatement(CtConstructor constructor, String statement) {
        return getReplaceStatement(constructor, false, statement);
    }

    protected String getReplaceStatement(
            CtConstructor constructor,
            boolean initializer,
            String statement) {
        MethodInfo methodInfo = constructor.getMethodInfo();
        int line = methodInfo.getLineNumber(0);
        String name = initializer ? "<clinit>" : "<init>";
        String className = constructor.getDeclaringClass().getName();
        ClassFile classFile2 = constructor.getDeclaringClass().getClassFile2();
        String fileName = classFile2 == null ? null : classFile2.getSourceFile();
        return getReplaceStatement(statement, line, name, className, fileName);
    }

    protected String getReplaceStatement(CtMethod method, String statement) {
        MethodInfo methodInfo = method.getMethodInfo();
        int line = methodInfo.getLineNumber(0);
        String name = method.getName();
        String className = method.getDeclaringClass().getName();
        ClassFile classFile2 = method.getDeclaringClass().getClassFile2();
        String fileName = classFile2 == null ? null : classFile2.getSourceFile();
        return getReplaceStatement(statement, line, name, className, fileName);
    }

    protected String replaceInstrument(
            CtMethod method,
            String statement)
            throws CannotCompileException {
        String replacement = getReplaceStatement(method, statement);
        try {
            String s = replacement.replaceAll("\n", "");
            method.setBody(s);
        } catch (CannotCompileException e) {
            Logger.error("Replace method instrument error with statement: "
                    + statement + "\n", e);
            throw new DroidAssistBadStatementException(e);
        }
        return replacement;
    }

    protected String replaceInstrument(
            MethodCall methodCall,
            String statement)
            throws CannotCompileException {
        String replacement = getReplaceStatement(methodCall, statement);
        try {
            String s = replacement.replaceAll("\n", "");
            methodCall.replace(s);
        } catch (CannotCompileException e) {
            Logger.error("Replace method call instrument error with statement: "
                    + statement + "\n", e);
            throw new DroidAssistBadStatementException(e);
        }
        return replacement;
    }

    protected String replaceInstrument(
            NewExpr expr,
            String statement)
            throws CannotCompileException {
        String replacement = getReplaceStatement(expr, statement);
        try {
            String s = replacement.replaceAll("\n", "");
            expr.replace(s);
        } catch (CannotCompileException e) {
            Logger.error("Replace new expr instrument error with statement: "
                    + statement + "\n", e);
            throw e;
        }
        return replacement;
    }

    protected String replaceInstrument(
            FieldAccess fieldAccess,
            String statement)
            throws CannotCompileException {
        String replacement = getReplaceStatement(fieldAccess, statement);
        try {
            String s = replacement.replaceAll("\n", "");
            fieldAccess.replace(s);
        } catch (CannotCompileException e) {
            Logger.error("Replace field access instrument error with statement: "
                    + statement + "\n", e);
            throw e;
        }
        return replacement;
    }

    protected String replaceInstrument(
            CtConstructor constructor,
            String statement)
            throws CannotCompileException {
        String replacement = getReplaceStatement(constructor, statement);
        try {
            String s = replacement.replaceAll("\n", "");
            constructor.setBody(s);
        } catch (CannotCompileException e) {
            Logger.error("Replace field access instrument error with statement: "
                    + statement + "\n", e);
            throw new DroidAssistBadStatementException(e);
        }
        return replacement;
    }

    /**
     * Replace placeholders with actual values
     *
     * <pre>$class</pre> represents class name
     * <pre>$line</pre> represents line number
     * <pre>$name</pre> represents method name or field name
     * <pre>$file</pre> represents file name
     */
    private String getReplaceStatement(
            String statement,
            int line,
            String name,
            String className,
            String fileName) {
        fileName = fileName == null ? "unknown" : fileName;
        className = className == null ? "unknown" : className;
        name = name == null ? "unknown" : name;
        statement = statement.replaceAll(
                Pattern.quote("$class"), Matcher.quoteReplacement(className));
        statement = statement.replaceAll(
                Pattern.quote("$line"), Matcher.quoteReplacement(String.valueOf(line)));
        statement = statement.replaceAll(
                Pattern.quote("$name"), Matcher.quoteReplacement(name));
        statement = statement.replaceAll(
                Pattern.quote("$file"), Matcher.quoteReplacement(fileName));
        return statement;
    }

    @Override
    public void check() {
    }

    @Override
    public String toString() {
        return getName() + "\n{" +
                "\n    source='" + source + '\'' +
                "\n    target='" + target + '\'' +
                "\n    filterClass=" + classFilterSpec +
                "\n}";
    }
}
