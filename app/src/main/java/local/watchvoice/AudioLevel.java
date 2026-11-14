package local.watchvoice;
final class AudioLevel {
    static float pcm16(byte[] samples) {
        if (samples == null || samples.length < 2) return 0f;
        int peak = 0;
        for (int i = 0; i + 1 < samples.length; i += 2) {
            int sample = (short) ((samples[i] & 255) | ((samples[i + 1] & 255) << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        return Math.min(1f, peak / 32768f);
    }
}
