package uk.co.benjaminelliott.spectrogramandroid.audioproc;

import java.util.concurrent.Semaphore;

import org.jtransforms.fft.DoubleFFT_1D;

import uk.co.benjaminelliott.spectrogramandroid.audioproc.windows.HammingWindow;
import uk.co.benjaminelliott.spectrogramandroid.audioproc.windows.WindowFunction;
import uk.co.benjaminelliott.spectrogramandroid.preferences.DynamicAudioConfig;

/**
 * A thread which takes audio windows generated by {link AudioCollector} 
 * and processes them into bitmap windows.
 * @author Ben
 *
 */
public class BitmapCreator extends Thread {

    boolean running = true; // whether or not this thread should process data
    private int samplesPerWindow; // number of audio samples per window
    private int numFreqBins; // number of frequency bins
    private short[][] audioWindows; // array of audio windows to be processed
    private int[][] bitmapWindows; // array of bitmap windows, created by processing audio windows
    private Semaphore audioReady; // semaphore that indicates if audio is available for processing
    private Semaphore bitmapsReady; // semaphore that indicates if bitmaps are available for display
    private WindowFunction window; // windowing function to apply to the audio windows
    private boolean arraysLooped = false; // whether or not the bitmapWindows array has been filled
    private int bitmapCurrentIndex = 0; // current index into the array of bitmap windows
    private int lastBitmapRequested = 0; // the most recently requested bitmap window
    private int oldestBitmapAvailable = 0; // the oldest valid bitmap available (older bitmaps are eventually overwritten)
    private double maxAmplitude = 1; // maximum amplitude recorded so far (used to determine relative colouring)
    private double contrast; // user's contrast preference
    private int[] colours; // array of spectrogram colours
    
    //allocate memory here rather than repeatedly re-allocating in performance-affecting methods:
    private double[] fftSamples;
    private double[] previousWindow; //keep a handle on the previous audio sample window so that values can be averaged across them
    private double[] combinedWindow;
    private DoubleFFT_1D dfft1d; //DoubleFFT_1D constructor must be supplied with an 'n' value, where n = data size
    private int val = 0; //current value for cappedValue function


    BitmapCreator(BitmapProvider bp) {
        this.audioWindows = bp.getAudioWindowArray();
        this.bitmapWindows = bp.getBitmapWindowArray();
        this.audioReady = bp.getAudioSemaphore();
        this.bitmapsReady = bp.getBitmapSemaphore();
        this.colours = bp.getColours();
        
        DynamicAudioConfig dac = bp.getDynamicAudioConfig();
        this.samplesPerWindow = dac.SAMPLES_PER_WINDOW;
        this.numFreqBins = dac.NUM_FREQ_BINS;
        this.contrast = dac.CONTRAST;
        
        window = new HammingWindow(samplesPerWindow);
        fftSamples = new double[samplesPerWindow];
        previousWindow = new double[samplesPerWindow];
        combinedWindow = new double[samplesPerWindow];
        dfft1d = new DoubleFFT_1D(samplesPerWindow);
    }

    @Override
    public void run() {
        while (running) {
            fillBitmapList();
        }
    }

    /**
     * When some audio data is ready, perform the short-time Fourier transform on it and 
     * then convert the results to a bitmap, which is then stored in a 2D array, ready to be displayed.
     */
    public void fillBitmapList() { 

        try {
        	// block thread until new audio is available for processing:
            audioReady.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // process audio window into corresponding bitmap window:
        processAudioWindow(audioWindows[bitmapCurrentIndex], bitmapWindows[bitmapCurrentIndex]);

        // increase bitmap window index:
        bitmapCurrentIndex++;
        // release semaphore to indicate a new bitmap window can be displayed:
        bitmapsReady.release();

        if (bitmapCurrentIndex == bitmapWindows.length) {
        	// if the end of the array has been reached, loop and start filling from the start again:
            bitmapCurrentIndex = 0;
            arraysLooped = true;
        }

        if (arraysLooped) {
        	// once the arrays have looped, the oldest valid bitmap is no longer at index 0 - instead it
        	// increases each time a new bitmap window is created:
            oldestBitmapAvailable++;
        }
    }
    
    /**
     * Take the raw audio samples, apply a windowing function, then perform the Short-Time
     * Fourier Transform and square the result. Combine the output with that from the previous window
     * for a smoothing effect. Return the resulting bitmap.
     */
   void processAudioWindow(short[] samples, int[] destArray) {

        for (int i = 0; i < samplesPerWindow; i++) {
            fftSamples[i] = (double)(samples[i]);
        }
        window.applyWindow(fftSamples); //apply Hamming window before performing STFT
        spectroTransform(fftSamples); //do the STFT on the copied data

        for (int i = 0; i < samplesPerWindow; i++) {
            combinedWindow[i] = fftSamples[i] + previousWindow[i];
        }

        for (int i = 0; i < numFreqBins; i++) {
            val = cappedValue(combinedWindow[i]);
            destArray[numFreqBins-i-1] = colours[val]; //fill upside-down because y=0 is at top of screen
        }

        //keep samples for next process
        for (int i = 0; i < samplesPerWindow; i++) previousWindow[i] = fftSamples[i];
    }

    /**
     * Returns an integer capped at 255 representing the magnitude of the
     * given double value, d, relative to the highest amplitude seen so far. The amplitude values
     * provided use a logarithmic scale but this method converts these back to a linear scale, 
     * more appropriate for pixel colouring.
     */
    private int cappedValue(double d) {
        if (d < 0) return 0;
        if (d > maxAmplitude) {
            maxAmplitude = d;
            return 255;
        }
        return (int)(255*Math.pow((Math.log1p(d)/Math.log1p(maxAmplitude)),contrast));
    }

    /**
     * Modifies the provided array of audio samples in-place, replacing them with 
     * the result of the short-time Fourier transform of the samples.
     *
     * See 'realForward' documentation of JTransforms for more information on the FFT implementation.
     */
    private void spectroTransform(double[] paddedSamples) {
        dfft1d.realForward(paddedSamples);
        //Calculate the STFT by using squared magnitudes. Store these in the first half of the array, and the rest will be discarded:
        for (int i = 0; i < numFreqBins; i++) {
            //Note that for frequency k, Re[k] and Im[k] are stored adjacently
            paddedSamples[i] = paddedSamples[2*i] * paddedSamples[2*i] + paddedSamples[2*i+1] * paddedSamples[2*i+1];
        }
    }
    
    public int getOldestBitmapIndex() {
        return oldestBitmapAvailable;
    }

    /**
     * Returns the index of the leftmost chronologically usable bitmap still in memory.
     */
    public int getLeftmostBitmapIndex() {
        if (!arraysLooped) return 0;
        return bitmapCurrentIndex + 1; //if array has looped, leftmost window is at current index + 1
    }

    /**
     *Returns the index of the rightmost chronologically usable bitmap still in memory.
     */
    public int getRightmostBitmapIndex() {
        return bitmapCurrentIndex;
    }

    /**
     * Returns a REFERENCE to the next bitmap window to be drawn, assuming that the caller will draw it before the bitmap 
     * creating thread overwrites it (the array size is large - drawing thread would have to be thousands of windows behind the 
     * creator thread). This potentially dangerous behaviour could be fixed with locks at the cost of performance.
     */
    public int[] getNextBitmap() {
        try {
            bitmapsReady.acquire(); //block until there is a bitmap to return
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (lastBitmapRequested == bitmapWindows.length) lastBitmapRequested = 0; //loop if necessary
        return bitmapWindows[lastBitmapRequested++];
    }
}