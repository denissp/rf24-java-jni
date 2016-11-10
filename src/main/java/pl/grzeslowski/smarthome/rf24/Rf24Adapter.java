package pl.grzeslowski.smarthome.rf24;


import pl.grzeslowski.smarthome.rf24.exceptions.CloseRf24Exception;
import pl.grzeslowski.smarthome.rf24.exceptions.InitRf24Exception;
import pl.grzeslowski.smarthome.rf24.exceptions.ReadRf24Exception;
import pl.grzeslowski.smarthome.rf24.exceptions.WriteRf24Exception;
import pl.grzeslowski.smarthome.rf24.generated.RF24;
import pl.grzeslowski.smarthome.rf24.helpers.Payload;
import pl.grzeslowski.smarthome.rf24.helpers.Pins;
import pl.grzeslowski.smarthome.rf24.helpers.Pipe;
import pl.grzeslowski.smarthome.rf24.helpers.Retry;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class Rf24Adapter implements BasicRf24 {
    public static final int MAX_NUMBER_OF_READING_PIPES = 5;
    private static final long DELAY_AFTER_STARTING_LISTENING = TimeUnit.SECONDS.convert(1, TimeUnit.MILLISECONDS);

    static {
        final String rf24Lib = "rf24bcmjava";
        try {
            System.loadLibrary(rf24Lib);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("Native code library (" + rf24Lib + ") failed to load.", e);
        }
    }

    private final Pins pins;
    private final Retry retry;
    private final Payload payload;

    private RF24 rf24;
    private List<Pipe> actualReadPipes;

    public Rf24Adapter(Pins pins, Retry retry, Payload payload) {
        if (pins == null) throw new NullPointerException("Pins cannot be null!");
        this.pins = pins;
        if (retry == null) throw new NullPointerException("Retry cannot be null!");
        this.retry = retry;
        if (payload == null) throw new NullPointerException("Payload cannot be null!");
        this.payload = payload;
    }

    /**
     * Gets object of {@link RF24} for further extending this class.
     *
     * @return active class used for communication with WiFi device (through JNI)
     */
    protected RF24 getRF24() {
        return rf24;
    }


    @Override
    public synchronized void init() {
        if (rf24 != null) {
            throw new IllegalStateException(format("RF24 is already initialized! You need to call close() first! RF24: [%s]", rf24));
        }
        try {
            rf24 = new RF24(pins.getCePin(), pins.getCsPin(), pins.getClockSpeed());
            rf24.setPayloadSize(payload.getSize());
            rf24.begin();
            rf24.setRetries(retry.getRetryDelay(), retry.getRetryNumber());
            startListening();
        } catch (Exception e) {
            throw new InitRf24Exception(e);
        }
    }

    private void startListening() {
        assert rf24 != null;
        rf24.startListening();
        try {
            TimeUnit.MILLISECONDS.sleep(DELAY_AFTER_STARTING_LISTENING);
        } catch (InterruptedException ignored) {
            // Ignore
        }
    }

    @Override
    public synchronized void close() {
        if (rf24 == null) {
            throw new IllegalStateException("RF24 was not initialized! Please call init() before calling close().");
        }
        try {
            rf24.delete();
        } catch (Error e) {
            throw new CloseRf24Exception(e);
        } finally {
            rf24 = null;
        }
    }

    @Override
    public synchronized boolean read(List<Pipe> readPipes, ByteBuffer buffer) {
        if (readPipes == null) throw new NullPointerException("Read pipes cannot be null!!");
        if (buffer == null) throw new NullPointerException("Buffer cannot be null!!");
        if (readPipes.isEmpty()) throw new IllegalArgumentException("There need to be at least 1 pipe to read from!");
        if (readPipes.size() > MAX_NUMBER_OF_READING_PIPES) {
            throw new IllegalArgumentException(format("There are too much reading pipes! Max: %s, was: %s.", MAX_NUMBER_OF_READING_PIPES, readPipes.size()));
        }

        try {
            trySetNewReadingPipes(readPipes);
            if (rf24.available()) {
                rf24.read(buffer.array(), (short) (buffer.capacity()));

                return true;
            } else {
                return false;
            }
        } catch (Error e) {
            throw new ReadRf24Exception(readPipes, e);
        }
    }

    private void trySetNewReadingPipes(List<Pipe> readPipes) {
        if (isNewReadingPipes(readPipes)) {
            rf24.stopListening();

            actualReadPipes = new ArrayList<>(readPipes.size());
            for (short i = 1; i <= readPipes.size(); i++) {
                final Pipe pipe = readPipes.get(i - 1);
                rf24.openReadingPipe(i, pipe.getBinaryPipe());
                actualReadPipes.add(i - 1, pipe);
            }

            startListening();
        }
    }

    private boolean isNewReadingPipes(List<Pipe> readPiped) {
        if (actualReadPipes == null) return true;

        if (actualReadPipes.size() != readPiped.size()) return true;

        for (int i = 0; i < actualReadPipes.size(); i++) {
            final Pipe actual = actualReadPipes.get(i);
            final Pipe pipe = readPiped.get(i);
            if (!actual.equals(pipe)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public synchronized boolean write(Pipe write, byte[] toSend) {
        if (write == null) throw new NullPointerException("Write pipe cannot nbe null!");
        if (toSend.length > rf24.getPayloadSize()) {
            throw new IllegalArgumentException(format("Bytes to send exceeds max payload size! Bytes size: %s, max payload size: %s",
                    toSend.length, rf24.getPayloadSize()));
        }

        try {
            rf24.stopListening();
            rf24.openWritingPipe(write.getBinaryPipe());
            return rf24.write(toSend, (short) toSend.length);
        } catch (Exception e) {
            throw new WriteRf24Exception(write, e);
        } finally {
            startListening();
        }
    }

    @Override
    public Payload getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        final String readPipes = String.join(",",
                actualReadPipes.stream()
                        .map(Pipe::getPipe)
                        .map(l -> Long.toString(l))
                        .collect(Collectors.toList())
        );
        return String.format("%s[init: %s, readPipes: %s, pins: %s, retry: %s, payload: %s]",
                this.getClass().getSimpleName(),
                rf24 != null,
                readPipes,
                pins,
                retry,
                payload);
    }
}
