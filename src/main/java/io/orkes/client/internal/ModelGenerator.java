package io.orkes.client.internal;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import lombok.SneakyThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ModelGenerator {

    private final JavaParser parser = new JavaParser();

    private final String cloneDir;

    private final String repoUrl;

    public ModelGenerator(String repoUrl, String cloneDir) {
        this.repoUrl = repoUrl;
        this.cloneDir = cloneDir;
    }

    @SneakyThrows
    public void cloneRepo() {
        var processBuilder = new ProcessBuilder("git", "clone", repoUrl, cloneDir);
        processBuilder.inheritIO();
        var process = processBuilder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to clone repository");
        }
    }

    public void processFiles(String source, String outputDir) {
        var sourceDir = cloneDir + source;
        listFiles(sourceDir)
                .stream()
                .filter(ModelGenerator::filter)
                .forEach(it -> processFile(it, sourceDir, outputDir));
    }

    private static boolean filter(Path file) {
        var str = file.toString();
        //TODO make this configurable
        boolean exclude = str.contains("/config/") ||
                str.contains("/jackson/") ||
                str.contains("/constraints/");
        return !exclude;
    }

    @SneakyThrows
    private List<Path> listFiles(String sourceDir) {
        try (Stream<Path> paths = Files.walk(Paths.get(sourceDir))) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    @SneakyThrows
    private void processFile(Path file, String sourceDir, String outputDir) {
        var result = parser.parse(file);
        if (!result.isSuccessful()) {
            throw new RuntimeException("Error parsing");
        }

        var cu = result.getResult()
                .orElseThrow(() -> new RuntimeException("Cannot get CompilationUnit"));
        repackage(cu);
        fixImports(cu);
        removeAnnotations(cu);

        String relativePath = file.toString().substring(sourceDir.length() + 1);
        Path outputFilePath = Paths.get(outputDir, relativePath);
        Files.createDirectories(outputFilePath.getParent());
        Files.write(outputFilePath, cu.toString().getBytes());
    }

    private void removeAnnotations(CompilationUnit cu) {
        cu.accept(new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(FieldDeclaration fd, Void arg) {
                fd.getAnnotations().clear();
                fd.getElementType().ifClassOrInterfaceType(type -> type.getAnnotations().clear());
                return super.visit(fd, arg);
            }

            @Override
            public Visitable visit(MethodDeclaration md, Void arg) {
                md.getAnnotations().removeIf(annotation -> !annotation.getNameAsString().equals("Deprecated"));
                md.getParameters().forEach(param -> param.getAnnotations().clear());
                return super.visit(md, arg);
            }

            @Override
            public Visitable visit(ClassOrInterfaceDeclaration cid, Void arg) {
                cid.getAnnotations().clear();
                cid.getMembers().forEach(member -> member.accept(this, arg));
                return super.visit(cid, arg);
            }

            @Override
            public Visitable visit(Parameter param, Void arg) {
                param.getAnnotations().clear();
                return super.visit(param, arg);
            }

            @Override
            public Visitable visit(TypeParameter typeParameter, Void arg) {
                typeParameter.getAnnotations().clear();
                return super.visit(typeParameter, arg);
            }

            @Override
            public Visitable visit(ClassOrInterfaceType type, Void arg) {
                type.getAnnotations().clear();
                return super.visit(type, arg);
            }

            @Override
            public Visitable visit(EnumDeclaration ed, Void arg) {
                ed.getAnnotations().removeIf(annotation -> !annotation.getNameAsString().equals("Deprecated"));
                return super.visit(ed, arg);
            }


        }, null);
    }

    private void fixImports(CompilationUnit cu) {
        cu.getImports().removeIf(importDecl ->
                importDecl.getNameAsString().startsWith("javax.validation")
                        // || importDecl.getNameAsString().startsWith("com.google.protobuf")
                        || importDecl.getNameAsString().startsWith("jakarta.validation")
                        || importDecl.getNameAsString().startsWith("com.netflix.conductor.annotations")
                        || importDecl.getNameAsString().startsWith("org.springframework")
                        || importDecl.getNameAsString().startsWith("jakarta.annotation")
                        || importDecl.getNameAsString().startsWith("io.swagger")
                        || importDecl.getNameAsString().startsWith("com.netflix.conductor.common.constraints")
        );

        cu.getImports().forEach(importDecl -> {
            String importName = importDecl.getNameAsString();
            if (importName.startsWith("com.netflix.conductor.common")) {
                importDecl.setName(importName.replace("com.netflix.conductor.common", "io.orkes.conductor.common"));
            }
        });
    }

    private void repackage(CompilationUnit cu) {
        cu.getPackageDeclaration().ifPresent(pkg -> {
            pkg.setName(pkg.getName().asString().replace("com.netflix.conductor.common", "io.orkes.conductor.common"));
        });
    }

    public static void main(String[] args) {
        var generator = new ModelGenerator("https://github.com/conductor-oss/conductor.git", "conductor-oss");
        //generator.cloneRepo();
        generator.processFiles(
                "/common/src/main/java/com/netflix/conductor/common",
                "output-dir/io/orkes/conductor/common");
    }
}
