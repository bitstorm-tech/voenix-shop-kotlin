#!/bin/sh

set -eu

if [ "$#" -lt 2 ]; then
  printf 'Usage: %s IMAGE_MODULE_JAR RUNTIME_JAR...\n' "$0" >&2
  exit 2
fi

for artifact in "$@"; do
  if [ ! -f "$artifact" ]; then
    printf 'Missing runtime artifact: %s\n' "$artifact" >&2
    exit 2
  fi
done

container_image=${IMAGE_RUNTIME_CONTAINER:-eclipse-temurin@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617}
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/voenix-image-runtime.XXXXXX")

cleanup() {
  rm -rf "$fixture_dir"
}
trap cleanup EXIT HUP INT TERM

cp "$1" "$fixture_dir/image-jvm.jar"
shift
for artifact in "$@"; do
  cp "$artifact" "$fixture_dir/$(basename "$artifact")"
done

cat >"$fixture_dir/CodecSmoke.java" <<'EOF'
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import shop.voenix.image.ImageCodec;
import shop.voenix.image.ImageSize;

public final class CodecSmoke {
    public static void main(String[] args) throws Exception {
        var source = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        var graphics = source.createGraphics();
        try {
            graphics.setColor(new Color(20, 90, 180, 160));
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            graphics.dispose();
        }

        var codec = new ImageCodec();
        var size = new ImageSize(120, 120);
        for (var format : ImageCodec.Format.values()) {
            var name = format.name().toLowerCase();
            var input = new File("/tmp/runtime-input." + name);
            var sourceForFormat = format == ImageCodec.Format.JPEG ? toRgb(source) : source;
            if (!ImageIO.write(sourceForFormat, name, input)) {
                throw new IllegalStateException("No input writer for " + format);
            }
            var decoded = codec.decode(input.toPath());
            if (decoded.getFormat() != format) {
                throw new IllegalStateException("Production decoder misidentified " + format);
            }
            var resized = size.resize(decoded.getImage());
            var output = Files.createTempFile("runtime-output-", "." + name);
            codec.write(resized, format, output);
            var roundTrip = ImageIO.read(output.toFile());
            if (roundTrip == null || roundTrip.getWidth() != 120 || roundTrip.getHeight() != 80) {
                throw new IllegalStateException("Production round trip failed for " + format);
            }
        }

        System.out.println(System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " production-codec-jpeg-png-webp-fit-ok");
    }

    private static BufferedImage toRgb(BufferedImage source) {
        var target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
EOF

docker run --rm --platform linux/arm64 \
  -v "$fixture_dir:/spike:ro" \
  "$container_image" \
  sh -c "mkdir -p /tmp/classes && javac -cp '/spike/*' -d /tmp/classes /spike/CodecSmoke.java && java --enable-native-access=ALL-UNNAMED -cp '/tmp/classes:/spike/*' CodecSmoke"
