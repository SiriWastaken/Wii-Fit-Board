import java.io.File;
import java.io.FileInputStream;

public class SimpleScale {
    public static void main(String[] args) {
        System.out.println("=== Ultra-Simple Wii Scale Running ===");
        
        // Once paired via blueutil, macOS mounts the board as a raw system event file
        File deviceFile = new File("/dev/cu.NintendoRVL-WBC-01"); 
        
        if (!deviceFile.exists()) {
            System.out.println("Looking for board... Please make sure the blue light is SOLID.");
            return;
        }

        try (FileInputStream stream = new FileInputStream(deviceFile)) {
            System.out.println("[SUCCESS] Scale Linked! Step on the board to see data.\n");
            
            byte[] buffer = new byte[22];
            while (stream.read(buffer) != -1) {
                // Pull raw byte values from the stream
                int topR = ((buffer[4] & 0xFF) << 8) | (buffer[5] & 0xFF);
                int botR = ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
                int topL = ((buffer[8] & 0xFF) << 8) | (buffer[9] & 0xFF);
                int botL = ((buffer[10] & 0xFF) << 8) | (buffer[11] & 0xFF);

                int totalRaw = topR + botR + topL + botL;
                double weightKg = totalRaw * 0.005; // Quick conversion factor

                System.out.printf("Weight Readout: %.2f kg\r", weightKg);
                Thread.sleep(20); // Sync with the board's transmission rate
            }
        } catch (Exception e) {
            System.out.println("Connection dropped. Try tapping the sync button again.");
        }
    }
}
