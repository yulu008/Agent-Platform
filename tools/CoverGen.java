import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * 随包预设封面占位图生成器（world-card-gallery 任务 4.1）。
 * ImageGen 额度不可用时的程序化兜底：1024x1536 竖版星空海报。
 * 用法: java tools/CoverGen.java <输出路径>
 */
public class CoverGen {
    public static void main(String[] args) throws Exception {
        int w = 1024, h = 1536;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Random rnd = new Random(20261013L);

        // 夜空渐变：深蓝 → 紫 → 地平线暖金
        g.setPaint(new GradientPaint(0, 0, new Color(8, 12, 46),
                0, h * 0.62f, new Color(38, 22, 84), true));
        g.fillRect(0, 0, w, h);
        g.setPaint(new GradientPaint(0, h * 0.60f, new Color(38, 22, 84),
                0, h, new Color(96, 58, 40), true));
        g.fillRect(0, (int) (h * 0.60f), w, h - (int) (h * 0.60f));

        // 银河光带（对角半透明宽带）
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.14f));
        g.setPaint(new GradientPaint(0, h * 0.18f, new Color(150, 170, 255),
                w, h * 0.52f, new Color(210, 170, 255), true));
        g.rotate(Math.toRadians(-24), w / 2.0, h * 0.35);
        g.fillRoundRect(-200, (int) (h * 0.26f), w + 400, 190, 190, 190);
        g.rotate(Math.toRadians(24), w / 2.0, h * 0.35);
        g.setComposite(AlphaComposite.SrcOver);

        // 星野：三层大小 + 亮度随机
        for (int i = 0; i < 420; i++) {
            int x = rnd.nextInt(w), y = (int) (rnd.nextFloat() * h * 0.72f);
            int r = rnd.nextInt(100) < 8 ? 3 : (rnd.nextInt(100) < 30 ? 2 : 1);
            float a = 0.35f + rnd.nextFloat() * 0.65f;
            g.setColor(new Color(255, 250, 235, (int) (a * 255)));
            g.fillOval(x, y, r * 2, r * 2);
            if (r == 3) { // 亮星十字光芒
                g.setStroke(new BasicStroke(1f));
                g.drawLine(x - 5, y + r, x + 7, y + r);
                g.drawLine(x + r, y - 5, x + r, y + 7);
            }
        }

        // 黄道星环（细金弧 + 节点星光）
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10f, new float[]{14f, 10f}, 0f));
        g.setColor(new Color(235, 196, 110, 150));
        g.drawArc(w / 2 - 360, (int) (h * 0.30f) - 360, 720, 720, 200, 250);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i < 12; i++) {
            double ang = Math.toRadians(200 + i * (250.0 / 11));
            int cx = (int) (w / 2.0 + 360 * Math.cos(ang));
            int cy = (int) (h * 0.30f + 360 * Math.sin(ang));
            g.setColor(new Color(250, 220, 150, 220));
            g.fillOval(cx - 3, cy - 3, 7, 7);
        }

        // 金色流星（带尾迹）
        int mx = (int) (w * 0.18f), my = (int) (h * 0.16f);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10f, new float[]{2f, 12f}, 0f));
        g.setPaint(new GradientPaint(mx + 300, my - 120, new Color(255, 214, 120, 0),
                mx, my, new Color(255, 240, 200, 255), true));
        g.drawLine(mx + 300, my - 120, mx, my);
        g.setColor(new Color(255, 246, 214));
        g.fillOval(mx - 5, my - 5, 11, 11);

        // 神殿剪影：阶梯 + 六柱 + 三角楣
        int baseY = h - 250;
        g.setColor(new Color(10, 8, 26));
        g.fillRect(0, baseY + 120, w, h - baseY - 120);          // 台基
        g.fillRect(90, baseY + 92, w - 180, 30);                  // 阶梯上沿
        g.fillRect(140, baseY + 62, w - 280, 32);                 // 柱础
        for (int i = 0; i < 6; i++) {                             // 立柱
            int cx = 205 + i * ((w - 410) / 5);
            g.fillRect(cx - 20, baseY - 190, 40, 252);
            g.fillRect(cx - 28, baseY - 202, 56, 14);             // 柱头
        }
        g.fillRect(150, baseY - 224, w - 300, 24);                // 檐部
        Path2D pediment = new Path2D.Double();                    // 三角楣
        pediment.moveTo(130, baseY - 224);
        pediment.lineTo(w / 2.0, baseY - 350);
        pediment.lineTo(w - 130, baseY - 224);
        pediment.closePath();
        g.fill(pediment);
        // 楣心金色徽光
        g.setColor(new Color(240, 205, 125, 235));
        g.fillOval(w / 2 - 16, baseY - 298, 32, 32);
        g.setColor(new Color(255, 236, 180, 120));
        g.fillOval(w / 2 - 30, baseY - 312, 60, 60);

        // 底部暗角压底，托住标题
        g.setPaint(new GradientPaint(0, h - 300, new Color(5, 4, 16, 0),
                0, h, new Color(5, 4, 16, 235), true));
        g.fillRect(0, h - 300, w, 300);

        // 标题
        Font title = new Font("Microsoft YaHei", Font.BOLD, 96);
        Font sub = new Font("Microsoft YaHei", Font.PLAIN, 34);
        g.setFont(title);
        String text = "圣战之始";
        FontMetrics fm = g.getFontMetrics();
        int tx = (w - fm.stringWidth(text)) / 2;
        g.setColor(new Color(232, 194, 110));
        g.drawString(text, tx, h - 150);
        g.setFont(sub);
        g.setColor(new Color(205, 200, 230, 220));
        String subText = "PRESET · ADVENTURE";
        fm = g.getFontMetrics();
        g.drawString(subText, (w - fm.stringWidth(subText)) / 2, h - 88);

        g.dispose();
        File out = new File(args[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("cover written: " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }
}
