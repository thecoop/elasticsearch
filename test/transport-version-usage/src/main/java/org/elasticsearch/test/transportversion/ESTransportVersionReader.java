/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test.transportversion;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

public class ESTransportVersionReader {
    public static final String VERSION_CLASS = "Lorg/elasticsearch/Version;";
    public static final String STREAMINPUT_INIT_DESC = "(Lorg/elasticsearch/common/io/stream/StreamInput;)V";
    public static final String WRITETO_DESC = "(Lorg/elasticsearch/common/io/stream/StreamOutput;)V";

    public static void main(String... args) throws Exception {
        System.out.println("Finding all writeable uses of org.elasticsearch.Version...");

        outputVersionUsages(args, System.out);
    }

    private static void outputVersionUsages(String[] classDirectories, PrintStream output) throws IOException {
        for (String classDirectory : classDirectories) {
            Path root = Paths.get(classDirectory);
            if (Files.isDirectory(root) == false) {
                throw new IllegalArgumentException(root + " should be an existing directory");
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".class")) {
                        try (InputStream in = Files.newInputStream(file)) {
                            outputUsages(in, output);
                        }
                    }
                    return super.visitFile(file, attrs);
                }
            });
        }
    }

    public static void outputUsages(InputStream inputStream, PrintStream out) throws IOException {
        ClassReader cr = new ClassReader(inputStream);
        cr.accept(new ClassOutput(out), ClassReader.SKIP_DEBUG);
    }

    private static class ClassOutput extends ClassVisitor {
        private final Set<String> referencedFields = new TreeSet<>();
        private final PrintStream out;
        private String className;

        ClassOutput(PrintStream out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (("writeTo".equals(name) && WRITETO_DESC.equals(desc))
                || ("<init>".equals(name) && STREAMINPUT_INIT_DESC.equals(desc))) {
                return new MethodChecker(referencedFields::add, access, name, desc, signature, exceptions);
            } else {
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        }

        @Override
        public void visitEnd() {
            if (referencedFields.isEmpty() == false) {
                out.printf("%s: %s%n", className.replace('/', '.'), referencedFields);
            }
            super.visitEnd();
        }
    }

    private static class MethodChecker extends MethodVisitor {
        private final Consumer<String> fieldReference;

        MethodChecker(Consumer<String> fieldReference, int access, String name, String desc, String signature, String[] exceptions) {
            super(Opcodes.ASM7, new MethodNode(access, name, desc, signature, exceptions));
            this.fieldReference = fieldReference;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC && descriptor.equals(VERSION_CLASS)) {
                fieldReference.accept(name);
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
}
