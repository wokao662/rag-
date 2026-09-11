package com.example.rag.cli;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Extracts text from a local document with Apache Tika. */
public final class DocumentExtractor {
    private static final long MAX_INPUT_BYTES = 50L * 1024 * 1024;

    private DocumentExtractor() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("用法: DocumentExtractor <输入文档> [输出文本]");
            System.exit(1);
        }

        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : defaultOutputPath(input);

        try {
            validateInput(input);
            Tika tika = new Tika();
            tika.setMaxStringLength(-1);

            String mediaType = tika.detect(input);
            String text = tika.parseToString(input).strip();

            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, text + System.lineSeparator(), StandardCharsets.UTF_8);

            System.out.printf("文档类型: %s%n", mediaType);
            System.out.printf("提取字符数: %d%n", text.length());
            System.out.printf("输出文件: %s%n", output);
        } catch (IOException | TikaException | IllegalArgumentException error) {
            System.err.println("文档解析失败: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void validateInput(Path input) throws IOException {
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("输入文件不存在或不是普通文件: " + input);
        }
        long size = Files.size(input);
        if (size == 0) {
            throw new IllegalArgumentException("输入文件为空: " + input);
        }
        if (size > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("输入文件超过 50 MB 限制: " + input);
        }
    }

    private static Path defaultOutputPath(Path input) {
        String fileName = input.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        return Path.of("data", "raw", baseName + ".txt").toAbsolutePath().normalize();
    }
}
