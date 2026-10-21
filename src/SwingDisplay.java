import javax.swing.*;
import java.awt.*;

/** Reference UI: one large weight, a status line, and a freeze button. Sizes scale with the window. */
final class SwingDisplay implements Display {

    private static final Color BG = new Color(0xFAFAF8);
    private static final Color INK = new Color(0x1C1C1C);
    private static final Color MUTED = new Color(0x6E6E6A);

    /** Below this the number is shown as 0 (sensor noise on an empty board). */
    private static final double DEADBAND_KG = 0.15;
    /** Fraction of the remaining distance the number moves each 16 ms frame. */
    private static final double EASING = 0.12;

    private volatile Snapshot snapshot = Snapshot.INITIAL;

    // Used only on the Swing thread.
    private boolean frozen;
    private double shownKg;
    private float lastFontPx;

    SwingDisplay(Config cfg) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> build(cfg));
    }

    @Override
    public void update(Snapshot s) {
        snapshot = s;
    }

    private void build(Config cfg) {
        JLabel weight = new JLabel("", SwingConstants.CENTER);
        weight.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 100));

        JLabel status = new JLabel(" ", SwingConstants.CENTER);
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        status.setForeground(MUTED);
        status.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton freeze = new JButton("Freeze") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(frozen ? INK : BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.setColor(INK);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.dispose();
                setForeground(frozen ? BG : INK);
                super.paintComponent(g);
            }
        };
        freeze.setContentAreaFilled(false);
        freeze.setBorderPainted(false);
        freeze.setFocusPainted(false);
        freeze.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        freeze.setPreferredSize(new Dimension(130, 44));
        freeze.setAlignmentX(Component.CENTER_ALIGNMENT);
        freeze.addActionListener(e -> {
            frozen = !frozen;
            freeze.setText(frozen ? "Resume" : "Freeze");
        });

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBorder(BorderFactory.createEmptyBorder(0, 16, 24, 16));
        south.add(status);
        south.add(Box.createVerticalStrut(16));
        south.add(freeze);

        JFrame frame = new JFrame("Scale");
        frame.getContentPane().setBackground(BG);
        frame.add(weight, BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        if (cfg.fullscreen) {
            frame.setUndecorated(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else {
            frame.setSize(520, 380);
            frame.setLocationRelativeTo(null);
        }

        new Timer(16, e -> {
            if (frozen) {
                return;
            }
            Snapshot s = snapshot;
            status.setText(s.detail);

            boolean live = s.state == MeasurementTracker.State.READY
                    || s.state == MeasurementTracker.State.STABILIZING
                    || s.state == MeasurementTracker.State.MEASUREMENT_COMPLETE
                    || s.state == MeasurementTracker.State.ERROR;
            float px = Math.max(40f, frame.getHeight() * 0.32f);
            if (px != lastFontPx) {
                weight.setFont(weight.getFont().deriveFont(px));
                lastFontPx = px;
            }
            if (!live) {
                weight.setForeground(MUTED);
                weight.setText("--");
                return;
            }
            double target = Double.isNaN(s.resultKg) ? s.liveKg : s.resultKg;
            shownKg += ((target < DEADBAND_KG ? 0 : target) - shownKg) * EASING;
            weight.setForeground(INK);
            weight.setText(String.format("<html>%.1f<span style='font-size:%dpx;color:#6E6E6A'> kg</span></html>",
                    Math.max(0, shownKg), (int) (px * 0.28)));
        }).start();

        frame.setVisible(true);
    }
}
