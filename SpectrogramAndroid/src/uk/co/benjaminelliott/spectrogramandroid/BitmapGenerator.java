package uk.co.benjaminelliott.spectrogramandroid;

import java.util.concurrent.Semaphore;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.preference.PreferenceManager;
import android.util.Log;
import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;

public class BitmapGenerator {

	/*
	 * Class which handles most of the 'clever stuff' behind the spectrogram display; taking in audio sample data
	 * from the microphone and processing it to give a list of bitmaps (each representing one window of the audio),
	 * ready to be displayed. Two threads are created: one to pull in data from the microphone ('audioThread') and 
	 * another to convert this data into a bitmap as soon as it becomes available ('bitmapThread').
	 */

	private final int SAMPLE_RATE; //options are 11025, 16000, 22050, 44100
	private final int SAMPLES_PER_WINDOW; //usually around 300
	private final int NUM_FREQ_BINS;
	public static final String PREF_COLOURMAP_KEY = "pref_colourmap";
	protected static final String PREF_CONTRAST_KEY = "pref_contrast";
	protected static final String PREF_SAMPLE_RATE_KEY = "pref_sample_rate";
	protected static final String PREF_SAMPLES_WINDOW_KEY = "pref_samples_window";

	//number of windows that can be held in the arrays at once before older ones are deleted. Time this represents is
	// WINDOW_LIMIT*SAMPLES_PER_WINDOW/SAMPLE_RATE, e.g. 10000*300/16000 = 187.5 seconds.
	protected static final int WINDOW_LIMIT = 1000; //usually around 10000 

	//Storage for audio and bitmap windows is pre-allocated, and the quantity is determined by
	// WINDOW_LIMIT*SAMPLES_PER_WINDOW*(bytes per int + bytes per double),
	// e.g. 10000*300*(4+8) = 34MB


	protected static final int BITMAP_STORE_WIDTH_ADJ = 2;
	protected static final int BITMAP_STORE_HEIGHT_ADJ = 2;
	protected static final int BITMAP_STORE_QUALITY = 90; //compression quality parameter for storage
	protected static final int BITMAP_FREQ_AXIS_WIDTH = 30; //number of pixels (subject to width adjustment) to use to display frequency axis on stored bitmaps

	private short[][] audioWindows;
	private int[][] bitmapWindows;

	private float contrast = 2.0f;

	private boolean running = false;
	private double maxAmplitude = 1; //max amplitude seen so far
	private AudioRecord mic;
	private Thread audioThread;
	private Thread bitmapThread;
	private int[] colours;
	private Integer audioCurrentIndex = 0; //keep track of where in the audioWindows array we have most recently written to
	private int bitmapCurrentIndex = 0;
	private boolean arraysLooped = false; //true if we have filled the entire array and are now looping round, hence old values can be read from later in the array
	private Semaphore audioReady = new Semaphore(0);
	private Semaphore bitmapsReady = new Semaphore(0);
	private int lastBitmapRequested = 0; //keeps track of the most recently requested bitmap window
	private int oldestBitmapAvailable = 0;
	private Context context;
	private HammingWindow hammingWindow;
	
	//allocate memory here rather than in performance-affecting methods:
	private int samplesRead = 0;
	private double[] fftSamples;
	private double[] previousWindow; //keep a handle on the previous audio sample window so that values can be averaged across them
	private double[] combinedWindow;
	private DoubleFFT_1D dfft1d; //DoubleFFT_1D constructor must be supplied with an 'n' value, where n = data size
	private int val = 0;

	public BitmapGenerator(Context context) {
		//bitmapsReady = new Semaphore(0);
		this.context = context;
		colours = new int[256];

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SAMPLE_RATE = Integer.parseInt(prefs.getString(PREF_SAMPLE_RATE_KEY, "16000"));
		SAMPLES_PER_WINDOW = Integer.parseInt(prefs.getString(PREF_SAMPLES_WINDOW_KEY, "300"));
		NUM_FREQ_BINS = SAMPLES_PER_WINDOW / 2; //lose half because of symmetry
		Log.d("","Sample rate: "+SAMPLE_RATE+", samples per window: "+SAMPLES_PER_WINDOW);
		
		audioWindows = new short[WINDOW_LIMIT][SAMPLES_PER_WINDOW];
		bitmapWindows = new int[WINDOW_LIMIT][NUM_FREQ_BINS];
		
		fftSamples = new double[SAMPLES_PER_WINDOW*2];
		previousWindow = new double[SAMPLES_PER_WINDOW]; //keep a handle on the previous audio sample window so that values can be averaged across them
		combinedWindow = new double[SAMPLES_PER_WINDOW];
		dfft1d = new DoubleFFT_1D(SAMPLES_PER_WINDOW); //DoubleFFT_1D constructor must be supplied with an 'n' value, where n = data size

		hammingWindow = new HammingWindow(SAMPLES_PER_WINDOW);
		
		String colMapString = prefs.getString(PREF_COLOURMAP_KEY, "NULL");
		int colourMap = 0;
		if (!colMapString.equals("NULL")) colourMap = Integer.parseInt(prefs.getString(PREF_COLOURMAP_KEY, "NULL"));

		switch (colourMap) {
		case 0: colours = HeatMap.whitePurpleGrouped(); break;
		case 1: colours = HeatMap.inverseGreyscale();break;
		case 2: colours = HeatMap.hotMetal(); break;
		case 3: colours = HeatMap.blueGreenRed(); break;
		case 4: colours = HeatMap.Greys_ColorBrewer(); break;
		case 5: colours = HeatMap.YlOrRd_ColorBrewer(); break;
		case 6: colours = HeatMap.PuOr_Backwards_ColorBrewer(); break;
		}
		
		float newContrast = prefs.getFloat(PREF_CONTRAST_KEY, Float.MAX_VALUE);
		if (newContrast != Float.MAX_VALUE) contrast = newContrast * 3.0f + 1.0f; //slider value must be between 0 and 1, so multiply by 3 and add 1

		int readSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		mic = new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT, readSize*2);
	}

	public void start() {
		/*
		 * Start the two threads responsible for bringing in audio samples and for processing them to generate bitmaps.
		 */

		mic.startRecording();
		running = true;
		audioThread = new Thread(){
			public void run() {
				while (running) fillAudioList();
				Log.d("AUDIO","Running false, audio terminating");
			}
		};
		audioThread.setName("Audio thread");

		bitmapThread = new Thread(){
			@Override
			public void run() {
				while (running) fillBitmapList();
				Log.d("BITMAP","Running false, bitmap terminating");
			}
		};
		bitmapThread.setName("Bitmap thread");

		audioThread.start();

		bitmapThread.start();
	}

	public void stop() {
		/*
		 * Stop bringing in and processing audio samples.
		 */
		if (running) {
			running = false;
			while (audioThread.isAlive()) {
				//this is hacky
			}
			mic.stop();
			mic.release();
			mic = null;
			Log.d("BG", "STOPPED");
		}
	}

	public void fillAudioList() {
		/*
		 * When audio data becomes available from the microphone, store it in a 2D array so
		 * that it remains available in case the user chooses to replay certain sections.
		 */
		//note no locking on audioWindows - dangerous but crucial for responsiveness
		readUntilFull(audioWindows[audioCurrentIndex], 0, SAMPLES_PER_WINDOW); //request samplesPerWindow shorts be written into the next free microphone buffer

		synchronized(audioCurrentIndex) { //don't modify this when it might be being read by another thread
			audioCurrentIndex++;
			audioReady.release();
			if (audioCurrentIndex == audioWindows.length) {
				//if entire array has been filled, loop and start filling from the start
				//Log.d("", "Adding audio item "+audioCurrentIndex+" and array full, so looping back to start");
				audioCurrentIndex = 0;

			}
		}
		//Log.d("Audio thread","Audio window "+audioCurrentIndex+" added.");
	}

	private void readUntilFull(short[] buffer, int offset, int spaceRemaining) {
		/*
		 * The 'read' method supplied by the AudioRecord class will not necessarily fill the destination
		 * buffer with samples if there is not enough data available. This method always returns a full array by
		 * repeatedly calling the 'read' method until there is no space left.
		 */
		while (spaceRemaining > 0) {
			samplesRead = mic.read(buffer, offset, spaceRemaining);
			spaceRemaining -= samplesRead;
			offset += samplesRead;
		}
	}

	public void fillBitmapList() { 
		/*
		 * When some audio data is ready, perform the short-time Fourier transform on it and 
		 * then convert the results to a bitmap, which is then stored in a 2D array, ready to be displayed.
		 */
		try {
			audioReady.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		processAudioWindow(audioWindows[bitmapCurrentIndex], bitmapWindows[bitmapCurrentIndex]);
		//Log.d("Bitmap thread","Audio window "+(bitmapCurrentIndex)+ " processed. ");

		bitmapCurrentIndex++;
		bitmapsReady.release();

		if (bitmapCurrentIndex == bitmapWindows.length) {
			Log.d("Bitmap: ","Arrays looped! Window limit "+WINDOW_LIMIT+", bitmapCurrentIndex: "+bitmapCurrentIndex);
			bitmapCurrentIndex = 0;
			arraysLooped = true;
		}
		
		if (arraysLooped) {
			oldestBitmapAvailable++;
		}
	}

	private void processAudioWindow(short[] samples, int[] destArray) {
		/*
		 * Take the raw audio samples, apply a Hamming window, then perform the Short-Time
		 * Fourier Transform and square the result. Combine the output with that from the previous window
		 * for a smoothing effect. Return the resulting bitmap.
		 */

		for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
			fftSamples[i] = (double)(samples[i]);
		}
		hammingWindow.applyHammingWindow(fftSamples); //apply Hamming window before performing STFT
		spectroTransform(fftSamples); //do the STFT on the copied data

		for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
			combinedWindow[i] = fftSamples[i] + previousWindow[i];
		}

		for (int i = 0; i < NUM_FREQ_BINS; i++) {
			val = cappedValue(combinedWindow[i]);
			destArray[NUM_FREQ_BINS-i-1] = colours[val]; //fill upside-down because y=0 is at top of screen
		}
		
		//keep samples for next process
		for (int i = 0; i < SAMPLES_PER_WINDOW; i++) previousWindow[i] = fftSamples[i];
	}

	private int cappedValue(double d) {
		/*
		 * Returns an integer capped at 255 representing the magnitude of the
		 * given double value, d, relative to the highest amplitude seen so far. The amplitude values
		 * provided use a logarithmic scale but this method converts these back to a linear scale, 
		 * more appropriate for pixel colouring.
		 */
		if (d < 0) return 0;
		if (d > maxAmplitude) {
			maxAmplitude = d;
			return 255;
		}
		return (int)(255*Math.pow((Math.log1p(d)/Math.log1p(maxAmplitude)),contrast));
	}

	private void spectroTransform(double[] paddedSamples) {
		/*
		 * This method modifies the provided array of audio samples in-place, replacing them with 
		 * the result of the short-time Fourier transform of the samples.
		 *
		 * See 'realForward' documentation of JTransforms for more information on the FFT implementation.
		 */

		dfft1d.realForward(paddedSamples);

		//Calculate the STFT by using squared magnitudes. Store these in the first half of the array, and the rest will be discarded:
		for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
			//Note that for frequency k, Re[k] and Im[k] are stored adjacently
			paddedSamples[i] = paddedSamples[2*i] * paddedSamples[2*i] + paddedSamples[2*i+1] * paddedSamples[2*i+1];
		}
	}

	protected int getBitmapWindowsAvailable() {
		/*
		 * Returns the number of bitmaps ready to be drawn.
		 */
		return bitmapsReady.availablePermits();
	}
	
	protected int getOldestBitmapAvailable() {
		return oldestBitmapAvailable;
	}

	protected int getLeftmostBitmapAvailable() {
		/*
		 * Returns the index of the leftmost chronologically usable bitmap still in memory.
		 */
		if (!arraysLooped) return 0;
		return bitmapCurrentIndex+1; //if array has looped, leftmost window is at current index + 1
	}

	protected int getRightmostBitmapAvailable() {
		/*
		 *Returns the index of the rightmost chronologically usable bitmap still in memory.
		 */
		return bitmapCurrentIndex; //just return the index of the last bitmap to have been processed
	}

	protected int[] getBitmapWindow(int index) {
		/*
		 * Returns the bitmap corresponding to the provided index into the array of bitmaps. No bounds checking.
		 */
		return bitmapWindows[index];
	}

	protected int[] getNextBitmap() {
		/*
		 * Returns a REFERENCE to the next bitmap window to be drawn, assuming that the caller will draw it before the bitmap 
		 * creating thread overwrites it (the array size is large - drawing thread would have to be thousands of windows behind the 
		 * creator thread). This potentially dangerous behaviour could be fixed with locks at the cost of performance.
		 */
		try {
			bitmapsReady.acquire(); //block until there is a bitmap to return
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		if (lastBitmapRequested == bitmapWindows.length) lastBitmapRequested = 0; //loop if necessary
		//Log.d("Spectro","Bitmap "+lastBitmapRequested+" requested");
		return bitmapWindows[lastBitmapRequested++];
	}

	protected Bitmap createEntireBitmap(int startWindow, int endWindow, int bottomFreq, int topFreq) {
		/*
		 * Returns a stand-alone bitmap with time from startWindow to endWindow and band-pass-filtered
		 * from bottomFreq to topFreq.
		 */
		//Hold on to string versions of the frequency values to annotate the bitmap later
		String bottomFreqText = Integer.toString(bottomFreq)+" Hz";
		String topFreqText = Integer.toString(topFreq)+" Hz";

		//convert frequency range into array indices
		bottomFreq = (int) ((2f*(float)bottomFreq/(float)SAMPLE_RATE)*NUM_FREQ_BINS);
		topFreq = (int) ((2f*(float)topFreq/(float)SAMPLE_RATE)*NUM_FREQ_BINS);

		//same for windows
		startWindow %= WINDOW_LIMIT;
		endWindow %= WINDOW_LIMIT;

		Bitmap ret;
		Canvas retCanvas;
		int bitmapWidth;
		int bitmapHeight;
		int[] window = new int[NUM_FREQ_BINS];
		int[] subsection;

		if (endWindow < startWindow) {
			//selection crosses a loop boundary
			bitmapWidth = (WINDOW_LIMIT - startWindow) + endWindow + BITMAP_FREQ_AXIS_WIDTH;
			bitmapHeight = topFreq - bottomFreq;

			subsection = new int[bitmapHeight];
			
			Log.d("BG", "Start window: "+startWindow+", end window: "+endWindow+", bottom freq as array index: "+bottomFreq+", top freq: "+topFreq);
			Log.d("BG", "Bitmap width: "+bitmapWidth+" bitmap height: "+bitmapHeight);

			ret = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
			retCanvas = new Canvas(ret);
			retCanvas.drawColor(Color.BLACK);


			for (int i = startWindow; i < WINDOW_LIMIT; i++) {
				window = new int[NUM_FREQ_BINS];
				processAudioWindow(audioWindows[i], window);
				for (int j = 0; j < topFreq - bottomFreq; j++) {
					subsection[bitmapHeight-j-1] = window[NUM_FREQ_BINS-(j+bottomFreq)-1]; //array was filled backwards
				}
				retCanvas.drawBitmap(subsection, 0, 1, BITMAP_FREQ_AXIS_WIDTH + i - startWindow, 0, 1, bitmapHeight, false, null);
			}

			for (int i = 0; i < endWindow; i++) {
				window = new int[NUM_FREQ_BINS];
				processAudioWindow(audioWindows[i], window);
				for (int j = 0; j < topFreq - bottomFreq; j++) {
					subsection[bitmapHeight-j-1] = window[NUM_FREQ_BINS-(j+bottomFreq)-1]; //array was filled backwards
				}
				retCanvas.drawBitmap(subsection, 0, 1, BITMAP_FREQ_AXIS_WIDTH + i - startWindow, 0, 1, bitmapHeight, false, null);
			}
			
		}
		else {
			bitmapWidth = endWindow - startWindow + BITMAP_FREQ_AXIS_WIDTH;
			bitmapHeight = topFreq - bottomFreq;
			
			subsection = new int[bitmapHeight];

			Log.d("BG", "Start window: "+startWindow+", end window: "+endWindow+", bottom freq as array index: "+bottomFreq+", top freq: "+topFreq);
			Log.d("BG", "Bitmap width: "+bitmapWidth+" bitmap height: "+bitmapHeight+" BFAW: "+BITMAP_FREQ_AXIS_WIDTH);

			ret = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
			retCanvas = new Canvas(ret);
			retCanvas.drawColor(Color.BLACK);
			
			for (int i = startWindow; i < endWindow; i++) {
				window = new int[NUM_FREQ_BINS];
				processAudioWindow(audioWindows[i], window);
				for (int j = 0; j < topFreq - bottomFreq; j++) {
					subsection[bitmapHeight-j-1] = window[NUM_FREQ_BINS-(j+bottomFreq)-1]; //array was filled backwards
				}
				retCanvas.drawBitmap(subsection, 0, 1, BITMAP_FREQ_AXIS_WIDTH + i - startWindow, 0, 1, bitmapHeight, false, null);
			}
			
		}
				
		Bitmap scaled = scaleBitmap(ret,bitmapWidth*BITMAP_STORE_WIDTH_ADJ, bitmapHeight*BITMAP_STORE_HEIGHT_ADJ);
		Canvas scaledCanvas = new Canvas(scaled);
		
		//annotate bitmap with frequency range:
		Paint textStyle = new Paint();
		textStyle.setColor(Color.WHITE);
		textStyle.setTextSize(BITMAP_FREQ_AXIS_WIDTH/3);
		scaledCanvas.drawText(bottomFreqText, BITMAP_FREQ_AXIS_WIDTH/2, bitmapHeight*BITMAP_STORE_HEIGHT_ADJ-5*BITMAP_STORE_HEIGHT_ADJ, textStyle);
		Log.d("Bitmap capture","bottomFreqText drawn at x:"+(BITMAP_FREQ_AXIS_WIDTH*BITMAP_STORE_WIDTH_ADJ/10)+" y: "+(bitmapHeight*BITMAP_STORE_HEIGHT_ADJ-5*BITMAP_STORE_HEIGHT_ADJ));
		scaledCanvas.drawText(topFreqText, BITMAP_FREQ_AXIS_WIDTH/2, BITMAP_FREQ_AXIS_WIDTH/2, textStyle);
		return scaled;
	}
	
	public Bitmap scaleBitmap(Bitmap bitmapToScale, float newWidth, float newHeight) {
		/*
		 * Returns the provided bitmap, scaled to fit the new width and height parameters.
		 */
		if(bitmapToScale == null)
			return null;
		//get the original width and height
		int width = bitmapToScale.getWidth();
		int height = bitmapToScale.getHeight();
		// create a matrix for the manipulation
		Matrix matrix = new Matrix();

		// resize the bit map
		matrix.postScale(newWidth / width, newHeight / height);

		// recreate the new Bitmap and set it back
		return Bitmap.createBitmap(bitmapToScale, 0, 0, bitmapToScale.getWidth(), bitmapToScale.getHeight(), matrix, true);
	}

	protected short[] getAudioChunk(int startWindow, int endWindow, int bottomFreq, int topFreq) {
		/*
		 * Returns an array of PCM audio data based on the window interval supplied to the function.
		 */
		//convert windows into array indices
		startWindow %= WINDOW_LIMIT;
		endWindow %= WINDOW_LIMIT;
		

		short[] toReturn;

		if (endWindow < startWindow) {
			//selection crosses a loop boundary
			toReturn = new short[((WINDOW_LIMIT - startWindow) + endWindow)*SAMPLES_PER_WINDOW];
			for (int i = startWindow; i < WINDOW_LIMIT; i++) {
				for (int j = 0; j < SAMPLES_PER_WINDOW; j++) {
					//Log.d("Audio chunk","i: "+i+", j: "+j+" i*SAMPLES_PER_WINDOW+j: "+(i*SAMPLES_PER_WINDOW+j));
					toReturn[(i-startWindow)*SAMPLES_PER_WINDOW+j] = audioWindows[i][j];
				}
			}
			for (int i = 0; i < endWindow; i++) {
				for (int j = 0; j < SAMPLES_PER_WINDOW; j++) {
					//Log.d("Audio chunk","i: "+i+", j: "+j+" i*SAMPLES_PER_WINDOW+j: "+(i*SAMPLES_PER_WINDOW+j));
					toReturn[(WINDOW_LIMIT-startWindow+i)*SAMPLES_PER_WINDOW+j] = audioWindows[i][j];
				}
			}
		}
		else {
			toReturn = new short[(endWindow-startWindow)*SAMPLES_PER_WINDOW];
			for (int i = startWindow; i < endWindow; i++) {

				for (int j = 0; j < SAMPLES_PER_WINDOW; j++) {
					//Log.d("Audio chunk","i: "+i+", j: "+j+" i*SAMPLES_PER_WINDOW+j: "+(i*SAMPLES_PER_WINDOW+j));
					toReturn[(i-startWindow)*SAMPLES_PER_WINDOW+j] = audioWindows[i][j];
				}
			}
		}
		
		Log.d("","Filtering capture from "+bottomFreq+"Hz to "+topFreq+"Hz.");
		BandpassButterworth butter = new BandpassButterworth(SAMPLE_RATE, 8, (double)bottomFreq, (double)topFreq, 1.0);
		butter.applyBandpassFilter(toReturn);
		
		for (int i = 0; i < toReturn.length; i++) toReturn[i] = Short.reverseBytes(toReturn[i]); //must be little-endian for WAV
		return toReturn;
	}

	protected void updateColourMap() {
		/*
		 * Called when the colour map preference is changed.
		 */
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		int newMap = Integer.parseInt(prefs.getString(PREF_COLOURMAP_KEY, "NULL"));
		Log.d("","NEW MAP: "+newMap);
		switch(newMap) {
		case 0: colours = HeatMap.whitePurpleGrouped(); break;
		case 1: colours = HeatMap.inverseGreyscale();break;
		case 2: colours = HeatMap.hotMetal(); break;
		case 3: colours = HeatMap.blueGreenRed(); break;
		}
	}

	protected void updateContrast() {
		/*
		 * Called when the colour map preference is changed.
		 */
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		float newContrast = prefs.getFloat(PREF_CONTRAST_KEY, Float.MAX_VALUE);
		if (newContrast != Float.MAX_VALUE) contrast = newContrast * 3.0f + 1.0f; //slider value must be between 0 and 1, so multiply by 3 and add 1
		Log.d("","NEW CONTRAST: "+newContrast);
	}
	
	protected int getSampleRate() {
		return SAMPLE_RATE;
	}
	
	protected int getSamplesPerWindow() {
		return SAMPLES_PER_WINDOW;
	}
	
	protected int getNumFreqBins() {
		return NUM_FREQ_BINS;
	}

}