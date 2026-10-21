import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wii Fit Board scale: one large weight readout and a freeze button.
 *
 * @author sriganty
 * @version 0.2
 */
public class Main {

    private static final Color BG = new Color(0xFAFAF8);
    private static final Color INK = new Color(0x1C1C1C);
    private static final Color MUTED = new Color(0x9A9A96);

    /** Raw samples averaged at startup to zero the empty board. */
    private static final int TARE_SAMPLES = 20;

    /** Readings below this are shown as 0 (sensor noise on an empty board). */
    private static final double DEADBAND_KG = 0.15;

    /** Latest tared weight from the board thread; NaN until zeroed. */
    private static volatile double target = Double.NaN;
    private static volatile String status = "Connecting";
    private static volatile boolean frozen = false;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        JLabel weight = new JLabel("", SwingConstants.CENTER);
        weight.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 120));
        weight.setForeground(INK);

        JButton freeze = new JButton("Freeze") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(frozen ? INK : BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.setColor(frozen ? INK : MUTED);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.dispose();
                setForeground(frozen ? BG : INK);
                super.paintComponent(g);
            }
        };
        freeze.setContentAreaFilled(false);
        freeze.setBorderPainted(false);
        freeze.setFocusPainted(false);
        freeze.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        freeze.setPreferredSize(new Dimension(120, 40));
        freeze.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel button = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder(0, 0, 40, 0));
        button.add(freeze);

        JFrame frame = new JFrame("Scale");
        frame.getContentPane().setBackground(BG);
        frame.add(weight, BorderLayout.CENTER);
        frame.add(button, BorderLayout.SOUTH);
        frame.setSize(520, 380);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Eases the shown value toward the target every frame so it never jumps.
        double[] shown = { 0 };
        new Timer(16, e -> {
            double t = target;
            if (Double.isNaN(t)) {
                weight.setFont(weight.getFont().deriveFont(36f));
                weight.setForeground(MUTED);
                weight.setText(status);
                return;
            }
            weight.setFont(weight.getFont().deriveFont(120f));
            weight.setForeground(INK);
            shown[0] += (t - shown[0]) * 0.12;
            weight.setText(String.format("<html>%.1f<span style='font-size:28px;color:#9A9A96'> kg</span></html>",
                    Math.max(0, shown[0])));
        }).start();

        freeze.addActionListener(e -> {
            if (Double.isNaN(target)) {
                return;
            }
            frozen = !frozen;
            if (frozen) {
                target = shown[0] < DEADBAND_KG ? 0 : shown[0];
            }
            freeze.setText(frozen ? "Resume" : "Freeze");
            freeze.repaint();
        });

        frame.setVisible(true);

        Thread reader = new Thread(Main::readLoop, "board");
        reader.setDaemon(true);
        reader.start();
    }

    /** Connects, zeroes the empty board, then publishes smoothed weights. */
    private static void readLoop() {
        WiiBoard board = new WiiBoard();
        board.connect();
        if (!board.isConnected()) {
            status = "No board";
            return;
        }
        try {
            status = "Zeroing";
            double zero = 0;
            double[] recent = new double[5];
            int n = 0, misses = 0;
            while (true) {
                Double kg = decode(board);
                if (kg == null) {
                    if (++misses >= 15) {
                        target = Double.NaN;
                        status = "Lost connection";
                        return;
                    }
                    continue;
                }
                misses = 0;
                recent[n++ % recent.length] = kg;

                // Median of the last 5 samples rejects single-sample spikes.
                double[] w = Arrays.copyOf(recent, Math.min(n, recent.length));
                Arrays.sort(w);
                double median = w[w.length / 2];

                if (n == TARE_SAMPLES) {
                    zero = median; // ponytail: assumes the board is empty at launch
                }
                if (n >= TARE_SAMPLES && !frozen) {
                    double v = median - zero;
                    target = v < DEADBAND_KG ? 0 : v;
                }
            }
        } finally {
            board.disconnect();
        }
    }

    private static Double decode(WiiBoard board) {
        byte[] report = board.readReport();
        if (report == null || report.length == 0) {
            return null;
        }
        List<Byte> bytes = new ArrayList<>();
        for (byte b : report) {
            bytes.add(b);
        }
        return board.decodeWeight(bytes);
    }
}
