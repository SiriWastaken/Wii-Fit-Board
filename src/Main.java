import javax.swing.*;
import java.awt.*;

/**
 * Wii Fit Board scale: one worker thread polls the board; a window shows the weight
 * (or, with no display, the status and results are printed).
 *
 * <p>
 * Run: {@code java -cp "out:lib/*" Main}. Add {@code -Dscale.debug=true} for a per-second
 * raw/total/report-rate line.
 * </p>
 *
 * @author sriganty
 * @version 0.4
 */
public class Main {

    private static final Color BG = new Color(0xFAFAF8);
    private static final Color INK = new Color(0x1C1C1C);
    private static final Color MUTED = new Color(0x6E6E6A);

    /** Below this the number is shown as 0 (sensor noise on an empty board). */
    private static final double DEADBAND_KG = 0.15;
    /** Fraction of the remaining distance the number moves each 16 ms frame. */
    private static final double EASING = 0.12;

    private static volatile boolean running = true;

    public static void main(String[] args) throws InterruptedException {
        WiiBoard board = new WiiBoard();

        Thread worker = new Thread(() -> {
            while (running) {
                board.poll();
            }
            board.disconnect();
        }, "board");
        worker.start();

        // Ctrl+C, SIGTERM or closing the window: stop polling, then release the HID device.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        if (GraphicsEnvironment.isHeadless()) {
            printLoop(board);
        } else {
            SwingUtilities.invokeLater(() -> showWindow(board));
        }
    }

    /** No display: print each status change and each accepted weight. */
    private static void printLoop(WiiBoard board) throws InterruptedException {
        String last = "";
        double lastResult = Double.NaN;
        while (running) {
            String status = board.status();
            if (!status.equals(last)) {
                System.out.println(status);
                last = status;
            }
            double result = board.resultKg();
            if (!Double.isNaN(result) && Double.isNaN(lastResult)) {
                System.out.printf("Weight: %.1f kg%n", result);
            }
            lastResult = result;
            Thread.sleep(100);
        }
    }

    private static void showWindow(WiiBoard board) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        JLabel weight = new JLabel("", SwingConstants.CENTER);
        weight.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 100));

        JLabel status = new JLabel(" ", SwingConstants.CENTER);
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        status.setForeground(MUTED);
        status.setAlignmentX(Component.CENTER_ALIGNMENT);

        boolean[] frozen = { false };
        JButton freeze = new JButton("Freeze") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(frozen[0] ? INK : BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.setColor(INK);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.dispose();
                setForeground(frozen[0] ? BG : INK);
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
            frozen[0] = !frozen[0];
            freeze.setText(frozen[0] ? "Resume" : "Freeze");
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
        frame.setSize(520, 380);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Eases the shown value toward the real one every frame so the number never jumps.
        double[] shownKg = { 0 };
        float[] fontPx = { 0 };
        new Timer(16, e -> {
            if (frozen[0]) {
                return;
            }
            status.setText(board.status());
            float px = Math.max(40f, frame.getHeight() * 0.32f);
            if (px != fontPx[0]) {
                weight.setFont(weight.getFont().deriveFont(px));
                fontPx[0] = px;
            }
            WiiBoard.State state = board.state();
            if (state == WiiBoard.State.OFFLINE || state == WiiBoard.State.ZEROING) {
                weight.setForeground(MUTED);
                weight.setText("--");
                return;
            }
            double target = Double.isNaN(board.resultKg()) ? board.liveKg() : board.resultKg();
            shownKg[0] += ((target < DEADBAND_KG ? 0 : target) - shownKg[0]) * EASING;
            weight.setForeground(INK);
            weight.setText(String.format("<html>%.1f<span style='font-size:%dpx;color:#6E6E6A'> kg</span></html>",
                    Math.max(0, shownKg[0]), (int) (px * 0.28)));
        }).start();

        frame.setVisible(true);
    }
}
