import javax.swing.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BooleanSupplier;

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

    private static final float REFERENCE_PX = 100f;
    private static final float MIN_PX = 12f;
    private static final int MARGIN_PX = 16;
    /** Unit labels and the kg line are drawn at these fractions of the pound number's size. */
    private static final double UNIT_SCALE = 0.3, KG_SCALE = 0.32;
    private static final double LB_PER_KG = 2.20462262185;
    /** How long a result stays on screen after the person steps off. */
    private static final long HOLD_MS = 15_000;

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
                System.out.printf("Weight: %.1f lb (%.1f kg)%n", result * LB_PER_KG, result);
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

        // Window state, used only on the Swing thread.
        boolean[] frozen = { false };
        boolean[] blank = { false }; // Reset pressed: show 0 until the next person steps on
        DefaultListModel<String> history = new DefaultListModel<>();

        JButton freeze = button("Freeze", () -> frozen[0]);
        JButton reset = button("Reset", () -> false);
        JButton historyButton = button("History", () -> false);
        freeze.addActionListener(e -> {
            frozen[0] = !frozen[0];
            freeze.setText(frozen[0] ? "Resume" : "Freeze");
        });
        reset.addActionListener(e -> blank[0] = true);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setOpaque(false);
        buttons.add(freeze);
        buttons.add(reset);
        buttons.add(historyButton);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBorder(BorderFactory.createEmptyBorder(0, 16, 24, 16));
        south.add(status);
        south.add(Box.createVerticalStrut(16));
        south.add(buttons);

        // Newest weighing first, timestamped, on the left.
        JList<String> historyList = new JList<>(history);
        historyList.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        historyList.setForeground(INK);
        historyList.setBackground(BG);
        historyList.setFocusable(false);
        JScrollPane historyPane = new JScrollPane(historyList);
        historyPane.setPreferredSize(new Dimension(230, 0));
        historyPane.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, MUTED));
        historyPane.setVisible(false);
        historyButton.addActionListener(e -> {
            historyPane.setVisible(!historyPane.isVisible());
            historyPane.getParent().revalidate();
        });

        JFrame frame = new JFrame("Scale");
        frame.getContentPane().setBackground(BG);
        frame.add(historyPane, BorderLayout.WEST);
        frame.add(weight, BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);
        frame.setSize(600, 480);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a");
        double[] shownKg = { 0 }; // eased toward the target every frame so the number never jumps
        double[] held = { Double.NaN }; // last result kept on screen after the person steps off
        double[] prevResult = { Double.NaN };
        long[] holdUntilMs = { 0 };
        float[] fontPx = { 0 };

        new Timer(16, e -> {
            long now = System.currentTimeMillis();
            WiiBoard.State state = board.state();

            // New accepted weight: log it, and keep showing it until HOLD_MS after the step-off.
            double result = board.resultKg();
            if (!Double.isNaN(result)) {
                if (Double.isNaN(prevResult[0])) {
                    history.add(0, "<html><span style='color:#6E6E6A'>" + LocalDateTime.now().format(stamp)
                            + "</span><br>" + describe(result) + "</html>");
                }
                held[0] = result;
                holdUntilMs[0] = Long.MAX_VALUE;
            } else if (!Double.isNaN(prevResult[0])) {
                holdUntilMs[0] = now + HOLD_MS;
            }
            prevResult[0] = result;
            if (state == WiiBoard.State.MEASURING) {
                blank[0] = false;
            }
            if (state == WiiBoard.State.OFFLINE || state == WiiBoard.State.ZEROING
                    || state == WiiBoard.State.MEASURING || now > holdUntilMs[0]) {
                held[0] = Double.NaN;
            }

            if (frozen[0]) {
                return;
            }
            status.setText(board.status());
            if (state == WiiBoard.State.OFFLINE || state == WiiBoard.State.ZEROING) {
                setSize(weight, "--", "", fontPx);
                weight.setForeground(MUTED);
                weight.setText("--");
                return;
            }
            double target = blank[0] ? 0 : !Double.isNaN(held[0]) ? held[0] : board.liveKg();
            shownKg[0] += ((target < DEADBAND_KG ? 0 : target) - shownKg[0]) * EASING;
            double kg = Math.max(0, shownKg[0]);
            String lbText = String.format("%.1f", kg * LB_PER_KG);
            String kgText = String.format("%.1f kg", kg);
            float px = setSize(weight, lbText + " lb", kgText, fontPx);
            weight.setForeground(INK);
            weight.setText(String.format("<html><center>%s<span style='font-size:%dpx;color:#6E6E6A'> lb</span>"
                    + "<br><span style='font-size:%dpx;color:#6E6E6A'>%s</span></center></html>",
                    lbText, (int) (px * UNIT_SCALE), (int) (px * KG_SCALE), kgText));
        }).start();

        frame.setVisible(true);
    }

    /**
     * Picks the largest font that fits both lines in the label (width and height), so long numbers
     * shrink instead of being clipped, whatever the window or history panel width.
     */
    private static float setSize(JLabel label, String lbLine, String kgLine, float[] last) {
        // Line widths scale linearly with font size, so measure at a reference size and divide.
        FontMetrics fm = label.getFontMetrics(label.getFont().deriveFont(REFERENCE_PX));
        String number = lbLine.endsWith(" lb") ? lbLine.substring(0, lbLine.length() - 3) : lbLine;
        double lbWidth = fm.stringWidth(number) + (lbLine.equals(number) ? 0 : UNIT_SCALE * fm.stringWidth(" lb"));
        double kgWidth = KG_SCALE * fm.stringWidth(kgLine);
        double perPx = Math.max(lbWidth, kgWidth) / REFERENCE_PX;
        double byWidth = (label.getWidth() - 2 * MARGIN_PX) / Math.max(perPx, 0.01);
        double byHeight = label.getHeight() * (kgLine.isEmpty() ? 0.7 : 0.5);
        float px = (float) Math.max(MIN_PX, Math.min(byWidth, byHeight));
        if (Math.abs(px - last[0]) >= 0.5f) {
            label.setFont(label.getFont().deriveFont(px));
            last[0] = px;
        }
        return px;
    }

    private static String describe(double kg) {
        return String.format("%.1f lb  /  %.1f kg", kg * LB_PER_KG, kg);
    }

    /** A rounded outline button, filled while {@code filled} is true. */
    private static JButton button(String text, BooleanSupplier filled) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(filled.getAsBoolean() ? INK : BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.setColor(INK);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 40, 40);
                g2.dispose();
                setForeground(filled.getAsBoolean() ? BG : INK);
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        b.setPreferredSize(new Dimension(110, 44));
        return b;
    }
}
