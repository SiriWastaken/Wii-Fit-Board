/** Pure decoding of Wii Balance Board HID input reports (report ID first). */
final class Reports {

    static final int STATUS = 0x20;
    static final int MEMORY_REPLY = 0x21;
    private static final int BYTES_PER_SENSOR = 2;

    private Reports() {
    }

    /**
     * Extracts the four raw load-cell values (big-endian 16-bit; top-right,
     * bottom-right, top-left, bottom-left) from a data report.
     *
     * @return the raw values, or {@code null} if the report is not a sensor report or is too short
     */
    static int[] sensors(byte[] r) {
        if (r == null || r.length == 0) {
            return null;
        }
        // 0x32/0x34: report ID + 2 button bytes before the sensors; 0x35 adds 3 accelerometer bytes.
        int id = r[0] & 0xFF;
        int offset = (id == 0x32 || id == 0x34) ? 3 : (id == 0x35) ? 6 : -1;
        if (offset < 0 || r.length < offset + Calibration.SENSORS * BYTES_PER_SENSOR) {
            return null;
        }
        int[] raw = new int[Calibration.SENSORS];
        for (int s = 0; s < raw.length; s++) {
            raw[s] = ((r[offset + 2 * s] & 0xFF) << 8) | (r[offset + 2 * s + 1] & 0xFF);
        }
        return raw;
    }

    /**
     * Copies the data of a memory-read reply (report 0x21: ID, 2 buttons, size/error,
     * 16-bit address, data) into {@code mem}, where {@code mem[0]} is address {@code base}.
     *
     * @return {@code false} if the reply is not a successful memory reply
     */
    static boolean copyMemoryReply(byte[] r, int base, byte[] mem, boolean[] seen) {
        if (r == null || r.length < 6 || (r[0] & 0xFF) != MEMORY_REPLY || (r[3] & 0x0F) != 0) {
            return false;
        }
        int len = ((r[3] & 0xF0) >> 4) + 1;
        int addr = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        for (int i = 0; i < len && 6 + i < r.length; i++) {
            int idx = addr + i - base;
            if (idx >= 0 && idx < mem.length) {
                mem[idx] = r[6 + i];
                seen[idx] = true;
            }
        }
        return true;
    }
}
