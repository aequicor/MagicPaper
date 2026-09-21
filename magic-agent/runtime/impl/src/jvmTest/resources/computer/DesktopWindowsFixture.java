import java.awt.Color;
import java.awt.Rectangle;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Separate process: capture exclusion must reveal this fixture, and clicks must cross a process boundary. */
public class DesktopWindowsFixture {
    public static void main(String[] args) throws Exception {
        JFrame[] frame = new JFrame[1];
        AtomicInteger clicks = new AtomicInteger();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        Rectangle bounds = new Rectangle(screen.x + 80, screen.y + 80, 320, 240);
        SwingUtilities.invokeAndWait(() -> {
            JButton target = new JButton() {
                @Override protected void paintComponent(java.awt.Graphics graphics) {
                    graphics.setColor(Color.BLUE);
                    graphics.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            target.getAccessibleContext().setAccessibleName("External fixture button");
            target.setBackground(Color.BLUE);
            target.setBorderPainted(false);
            target.setFocusPainted(false);
            target.setOpaque(true);
            target.addActionListener(event -> clicks.incrementAndGet());
            frame[0] = new JFrame("MagicPaper external Windows fixture");
            frame[0].setUndecorated(true);
            frame[0].add(target);
            frame[0].setBounds(bounds);
            frame[0].setAlwaysOnTop(true);
            frame[0].setVisible(true);
        });
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (input.readLine() != null) {
                System.out.printf("{\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d,\"clicks\":%d}%n",
                    bounds.x, bounds.y, bounds.width, bounds.height, clicks.get());
                System.out.flush();
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }
}
