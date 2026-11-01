package com.example.dormitory.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

public class CaptchaUtil {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_LENGTH = 4;
    private static final int LINE_COUNT = 8;
    private static final int DOT_COUNT = 50;

    private static final char[] CODE_CHARS = "0123456789".toCharArray();

    private static final Color[] COLORS = {
            new Color(255, 107, 107),
            new Color(78, 205, 196),
            new Color(155, 89, 182),
            new Color(52, 152, 219),
            new Color(46, 204, 113),
            new Color(243, 156, 18)
    };

    private static Random random = new java.security.SecureRandom();

    public static String generateCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        }
        return code.toString();
    }

    public static byte[] generateImage(String code) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        for (int i = 0; i < LINE_COUNT; i++) {
            g.setColor(getRandomColor());
            int x1 = random.nextInt(WIDTH);
            int y1 = random.nextInt(HEIGHT);
            int x2 = random.nextInt(WIDTH);
            int y2 = random.nextInt(HEIGHT);
            g.drawLine(x1, y1, x2, y2);
        }

        for (int i = 0; i < DOT_COUNT; i++) {
            g.setColor(getRandomColor());
            int x = random.nextInt(WIDTH);
            int y = random.nextInt(HEIGHT);
            g.fillOval(x, y, 2, 2);
        }

        Font font = new Font("Arial", Font.BOLD, 28);
        g.setFont(font);

        for (int i = 0; i < code.length(); i++) {
            g.setColor(COLORS[random.nextInt(COLORS.length)]);
            char c = code.charAt(i);
            int x = 15 + i * 25;
            int y = HEIGHT / 2 + 10;
            double angle = (random.nextInt(30) - 15) * Math.PI / 180;
            g.rotate(angle, x, y);
            g.drawString(String.valueOf(c), x, y);
            g.rotate(-angle, x, y);
        }

        g.dispose();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);
        return outputStream.toByteArray();
    }

    private static Color getRandomColor() {
        int r = random.nextInt(150) + 50;
        int g = random.nextInt(150) + 50;
        int b = random.nextInt(150) + 50;
        return new Color(r, g, b);
    }
}