package ar.com.hjg.pngj;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import ar.com.hjg.pngj.chunks.ChunkCopyBehaviour;
import ar.com.hjg.pngj.chunks.ChunkHelper;
import ar.com.hjg.pngj.chunks.ChunksList;
import ar.com.hjg.pngj.chunks.ChunksListForWrite;
import ar.com.hjg.pngj.chunks.PngChunk;
import ar.com.hjg.pngj.chunks.PngChunkIEND;
import ar.com.hjg.pngj.chunks.PngChunkIHDR;
import ar.com.hjg.pngj.chunks.PngChunkTextVar;
import ar.com.hjg.pngj.chunks.PngChunkUnbuffered;
import ar.com.hjg.pngj.chunks.PngMetadata;

/**
 * Writes a PNG image
 */
public class PngWriter {

	public final ImageInfo imgInfo;

	private final String filename; // optional, can be a description

	/**
	 * last writen row number, starting from 0
	 */
	protected int rowNum = -1;

	private final ChunksListForWrite chunksList;

	private final PngMetadata metadata; // high level wrapper over chunkList

	/**
	 * Current chunk grounp, (0-6) already read or reading
	 * <p>
	 * see {@link ChunksList}
	 */
	protected int currentChunkGroup = -1;

	/**
	 * PNG filter strategy
	 */
	protected FilterWriteStrategy filterStrat;

	/**
	 * zip compression level 0 - 9
	 */
	private int compLevel = 6;
	private boolean shouldCloseStream = true; // true: closes stream after ending write

	private PngIDatChunkOutputStream datStream;

	private DeflaterOutputStream datStreamDeflated;

	/**
	 * Deflate algortithm compression strategy
	 */
	private int deflaterStrategy = Deflater.FILTERED;

	private int[] histox = new int[256]; // auxiliar buffer, only used by reportResultsForFilter

	private int idatMaxSize = 0; // 0=use default (PngIDatChunkOutputStream 32768)

	private final OutputStream os;

	protected byte[] rowb = null; // element 0 is filter type!
	protected byte[] rowbfilter = null; // current line with filter

	protected byte[] rowbprev = null; // rowb prev

	public PngWriter(File file, ImageInfo imgInfo, boolean allowoverwrite) {
		this(PngHelperInternal.ostreamFromFile(file, allowoverwrite), imgInfo);
		setShouldCloseStream(true);
	}

	public PngWriter(File file, ImageInfo imgInfo) {
		this(file, imgInfo, true);
	}

	/**
	 * Constructs a new PngWriter from a output stream. After construction
	 * nothing is writen yet. You still can set some parameters (compression,
	 * filters) and queue chunks before start writing the pixels.
	 * <p>
	 * See also <code>FileHelper.createPngWriter()</code> if available.
	 * 
	 * @param outputStream
	 *            Opened stream for binary writing
	 * @param imgInfo
	 *            Basic image parameters
	 * @param filenameOrDescription
	 *            Optional, just for error/debug messages
	 */
	public PngWriter(OutputStream outputStream, ImageInfo imgInfo) {
		this.filename = "";
		this.os = outputStream;
		this.imgInfo = imgInfo;
		// prealloc
		rowb = new byte[imgInfo.bytesPerRow + 1];
		rowbprev = new byte[rowb.length];
		rowbfilter = new byte[rowb.length];
		chunksList = new ChunksListForWrite(imgInfo);
		metadata = new PngMetadata(chunksList);
		filterStrat = new FilterWriteStrategy(imgInfo, FilterType.FILTER_DEFAULT); // can be changed
	}

	private void init() {
		datStream = new PngIDatChunkOutputStream(this.os, idatMaxSize);
		Deflater def = new Deflater(compLevel);
		def.setStrategy(deflaterStrategy);
		datStreamDeflated = new DeflaterOutputStream(datStream, def);
		writeSignatureAndIHDR();
		writeFirstChunks();
	}

	private void reportResultsForFilter(int rown, FilterType type, boolean tentative) {
		Arrays.fill(histox, 0);
		int s = 0, v;
		for (int i = 1; i <= imgInfo.bytesPerRow; i++) {
			v = rowbfilter[i];
			if (v < 0)
				s -= (int) v;
			else
				s += (int) v;
			histox[v & 0xFF]++;
		}
		filterStrat.fillResultsForFilter(rown, type, s, histox, tentative);
	}

	private void writeEndChunk() {
		PngChunkIEND c = new PngChunkIEND(imgInfo);
		c.createRawChunk().writeChunk(os);
	}

	private void writeFirstChunks() {
		int nw = 0;
		currentChunkGroup = ChunksList.CHUNK_GROUP_1_AFTERIDHR;
		nw = chunksList.writeChunks(os, currentChunkGroup);
		currentChunkGroup = ChunksList.CHUNK_GROUP_2_PLTE;
		nw = chunksList.writeChunks(os, currentChunkGroup);
		if (nw > 0 && imgInfo.greyscale)
			throw new PngjOutputException("cannot write palette for this format");
		if (nw == 0 && imgInfo.indexed)
			throw new PngjOutputException("missing palette");
		currentChunkGroup = ChunksList.CHUNK_GROUP_3_AFTERPLTE;
		nw = chunksList.writeChunks(os, currentChunkGroup);
		currentChunkGroup = ChunksList.CHUNK_GROUP_4_IDAT;
	}

	private void writeLastChunks() { // not including end
		currentChunkGroup = ChunksList.CHUNK_GROUP_5_AFTERIDAT;
		chunksList.writeChunks(os, currentChunkGroup);
		// should not be unwriten chunks
		List<PngChunk> pending = chunksList.getQueuedChunks();
		if (!pending.isEmpty())
			throw new PngjOutputException(pending.size() + " chunks were not written! Eg: " + pending.get(0).toString());
		currentChunkGroup = ChunksList.CHUNK_GROUP_6_END;
	}

	/**
	 * Write id signature and also "IHDR" chunk
	 */
	private void writeSignatureAndIHDR() {
		currentChunkGroup = ChunksList.CHUNK_GROUP_0_IDHR;

		PngHelperInternal.writeBytes(os, PngHelperInternal.getPngIdSignature()); // signature
		PngChunkIHDR ihdr = new PngChunkIHDR(imgInfo);
		// http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html
		ihdr.setCols(imgInfo.cols);
		ihdr.setRows(imgInfo.rows);
		ihdr.setBitspc(imgInfo.bitDepth);
		int colormodel = 0;
		if (imgInfo.alpha)
			colormodel += 0x04;
		if (imgInfo.indexed)
			colormodel += 0x01;
		if (!imgInfo.greyscale)
			colormodel += 0x02;
		ihdr.setColormodel(colormodel);
		ihdr.setCompmeth(0); // compression method 0=deflate
		ihdr.setFilmeth(0); // filter method (0)
		ihdr.setInterlaced(0); // we never interlace
		ihdr.createRawChunk().writeChunk(os);

	}

	private void filterRow() {
		// warning: filters operation rely on: "previos row" (rowbprev) is
		// initialized to 0 the first time
		if (filterStrat.shouldTestAll(rowNum)) {
			filterRowNone();
			reportResultsForFilter(rowNum, FilterType.FILTER_NONE, true);
			filterRowSub();
			reportResultsForFilter(rowNum, FilterType.FILTER_SUB, true);
			filterRowUp();
			reportResultsForFilter(rowNum, FilterType.FILTER_UP, true);
			filterRowAverage();
			reportResultsForFilter(rowNum, FilterType.FILTER_AVERAGE, true);
			filterRowPaeth();
			reportResultsForFilter(rowNum, FilterType.FILTER_PAETH, true);
		}
		FilterType filterType = filterStrat.gimmeFilterType(rowNum, true);
		rowbfilter[0] = (byte) filterType.val;
		switch (filterType) {
		case FILTER_NONE:
			filterRowNone();
			break;
		case FILTER_SUB:
			filterRowSub();
			break;
		case FILTER_UP:
			filterRowUp();
			break;
		case FILTER_AVERAGE:
			filterRowAverage();
			break;
		case FILTER_PAETH:
			filterRowPaeth();
			break;
		default:
			throw new PngjUnsupportedException("Filter type " + filterType + " not implemented");
		}
		reportResultsForFilter(rowNum, filterType, false);
	}

	private void filterAndSend() {
		filterRow();
		try {
			datStreamDeflated.write(rowbfilter, 0, imgInfo.bytesPerRow + 1);
		} catch (IOException e) {
			throw new PngjOutputException(e);
		}
	}

	protected void filterRowAverage() {
		int i, j, imax;
		imax = imgInfo.bytesPerRow;
		for (j = 1 - imgInfo.bytesPixel, i = 1; i <= imax; i++, j++) {
			rowbfilter[i] = (byte) (rowb[i] - ((rowbprev[i] & 0xFF) + (j > 0 ? (rowb[j] & 0xFF) : 0)) / 2);
		}
	}

	protected void filterRowNone() {
		for (int i = 1; i <= imgInfo.bytesPerRow; i++) {
			rowbfilter[i] = (byte) rowb[i];
		}
	}

	protected void filterRowPaeth() {
		int i, j, imax;
		imax = imgInfo.bytesPerRow;
		for (j = 1 - imgInfo.bytesPixel, i = 1; i <= imax; i++, j++) {
			// rowbfilter[i] = (byte) (rowb[i] - PngHelperInternal.filterPaethPredictor(j > 0 ? (rowb[j] & 0xFF) : 0,
			// rowbprev[i] & 0xFF, j > 0 ? (rowbprev[j] & 0xFF) : 0));
			rowbfilter[i] = (byte) PngHelperInternal.filterRowPaeth(rowb[i], j > 0 ? (rowb[j] & 0xFF) : 0,
					rowbprev[i] & 0xFF, j > 0 ? (rowbprev[j] & 0xFF) : 0);
		}
	}

	protected void filterRowSub() {
		int i, j;
		for (i = 1; i <= imgInfo.bytesPixel; i++)
			rowbfilter[i] = (byte) rowb[i];
		for (j = 1, i = imgInfo.bytesPixel + 1; i <= imgInfo.bytesPerRow; i++, j++) {
			// !!! rowbfilter[i] = (byte) (rowb[i] - rowb[j]);
			rowbfilter[i] = (byte) PngHelperInternal.filterRowSub(rowb[i], rowb[j]);
		}
	}

	protected void filterRowUp() {
		for (int i = 1; i <= imgInfo.bytesPerRow; i++) {
			// rowbfilter[i] = (byte) (rowb[i] - rowbprev[i]); !!!
			rowbfilter[i] = (byte) PngHelperInternal.filterRowUp(rowb[i], rowbprev[i]);
		}
	}

	protected int sumRowbfilter() { // sums absolute value
		int s = 0;
		for (int i = 1; i <= imgInfo.bytesPerRow; i++)
			if (rowbfilter[i] < 0)
				s -= (int) rowbfilter[i];
			else
				s += (int) rowbfilter[i];
		return s;
	}

	/**
	 * copy chunks from reader - copy_mask : see ChunksToWrite.COPY_XXX
	 * <p>
	 * If we are after idat, only considers those chunks after IDAT in PngReader
	 * <p>
	 * TODO: this should be more customizable
	 */
	private void copyChunks(ChunksList reader, int copy_mask, boolean onlyAfterIdat) {
		boolean idatDone = currentChunkGroup >= ChunksList.CHUNK_GROUP_4_IDAT;
		for (PngChunk chunk : reader.getChunks()) {
			int group = chunk.getChunkGroup();
			if (group < ChunksList.CHUNK_GROUP_4_IDAT && idatDone)
				continue;
			if (chunk.getRaw().data == null)
				continue; // skipped chunk?
			boolean copy = false;
			if (chunk.crit) {
				if (chunk.id.equals(ChunkHelper.PLTE)) {
					if (imgInfo.indexed && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_PALETTE))
						copy = true;
					if (!imgInfo.greyscale && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALL))
						copy = true;
				}
			} else { // ancillary
				boolean text = (chunk instanceof PngChunkTextVar);
				boolean safe = chunk.safe;
				// notice that these if are not exclusive
				if (ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALL))
					copy = true;
				if (safe && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALL_SAFE))
					copy = true;
				if (chunk.id.equals(ChunkHelper.tRNS)
						&& ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_TRANSPARENCY))
					copy = true;
				if (chunk.id.equals(ChunkHelper.pHYs) && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_PHYS))
					copy = true;
				if (text && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_TEXTUAL))
					copy = true;
				if (ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALMOSTALL)
						&& !(ChunkHelper.isUnknown(chunk) || text || chunk.id.equals(ChunkHelper.hIST) || chunk.id
								.equals(ChunkHelper.tIME)))
					copy = true;
				if (chunk instanceof PngChunkUnbuffered)
					copy = false;
			}
			if (copy) {
				chunksList.queue(chunk.cloneForWrite(imgInfo));
			}
		}
	}

	/**
	 * Copies first (pre IDAT) ancillary chunks from a PngReader.
	 * <p>
	 * Should be called when creating an image from another, before starting
	 * writing lines, to copy relevant chunks.
	 * <p>
	 * 
	 * @param reader
	 *            : PngReader object, already opened.
	 * @param copy_mask
	 *            : Mask bit (OR), see <code>ChunksToWrite.COPY_XXX</code>
	 *            constants
	 */
	public void copyChunksFirst(ChunksList reader, int copy_mask) {
		copyChunks(reader, copy_mask, false);
	}

	/**
	 * Copies last (post IDAT) ancillary chunks from a PngReader.
	 * <p>
	 * Should be called when creating an image from another, after writing all
	 * lines, before closing the writer, to copy additional chunks.
	 * <p>
	 * 
	 * @param reader
	 *            : PngReader object, already opened and fully read.
	 * @param copy_mask
	 *            : Mask bit (OR), see <code>ChunksToWrite.COPY_XXX</code>
	 *            constants
	 */
	public void copyChunksLast(ChunksList reader, int copy_mask) {
		copyChunks(reader, copy_mask, true);
	}

	/**
	 * Computes compressed size/raw size, approximate.
	 * <p>
	 * Actually: compressed size = total size of IDAT data , raw size =
	 * uncompressed pixel bytes = rows * (bytesPerRow + 1).
	 * 
	 * This must be called after pngw.end()
	 */
	public double computeCompressionRatio() {
		if (currentChunkGroup < ChunksList.CHUNK_GROUP_6_END)
			throw new PngjOutputException("must be called after end()");
		double compressed = (double) datStream.getCountFlushed();
		double raw = (imgInfo.bytesPerRow + 1) * imgInfo.rows;
		return compressed / raw;
	}

	/**
	 * Finalizes the image creation and closes the stream. This MUST be called
	 * after writing the lines.
	 */
	public void end() {
		if (rowNum != imgInfo.rows - 1)
			throw new PngjOutputException("all rows have not been written");
		try {
			datStreamDeflated.finish();
			datStream.flush();
			writeLastChunks();
			writeEndChunk();
		} catch (IOException e) {
			throw new PngjOutputException(e);
		} finally {
			close();
		}
	}

	/** releases resources (stream) */
	public void close() {
		if (shouldCloseStream)
			try {
				os.close();
			} catch (Exception e) {
				PngHelperInternal.LOGGER.warning("Error closing writer " + e.toString());
			}
	}

	/**
	 * returns the chunks list (queued and writen chunks)
	 */
	public ChunksListForWrite getChunksList() {
		return chunksList;
	}

	/**
	 * Filename or description, from the optional constructor argument.
	 */
	public String getFilename() {
		return filename;
	}

	/**
	 * High level wrapper over chunksList for metadata handling
	 */
	public PngMetadata getMetadata() {
		return metadata;
	}

	/**
	 * Sets compression level of ZIP algorithm.
	 * <p>
	 * This must be called just after constructor, before starting writing.
	 * <p>
	 * See also setFilterType()
	 * 
	 * @param compLevel
	 *            between 0 and 9 (default:6 , recommended: 6 or more)
	 */
	public void setCompLevel(int compLevel) {
		if (compLevel < 0 || compLevel > 9)
			throw new PngjOutputException("Compression level invalid (" + compLevel + ") Must be 0..9");
		this.compLevel = compLevel;
	}

	/**
	 * Sets internal prediction filter type, or strategy to choose it.
	 * <p>
	 * This must be called just after constructor, before starting writing.
	 * <p>
	 * See also setCompLevel()
	 * 
	 * @param filterType
	 *            One of the five prediction types or strategy to choose it (see
	 *            <code>PngFilterType</code>) Recommended values: DEFAULT
	 *            (default) or AGGRESIVE
	 */
	public void setFilterType(FilterType filterType) {
		filterStrat = new FilterWriteStrategy(imgInfo, filterType);
	}

	/**
	 * Sets maximum size of IDAT fragments. This has little effect on
	 * performance you should rarely call this
	 * <p>
	 * 
	 * @param idatMaxSize
	 *            default=0 : use defaultSize (32K)
	 */
	public void setIdatMaxSize(int idatMaxSize) {
		this.idatMaxSize = idatMaxSize;
	}

	/**
	 * if true, input stream will be closed after ending write
	 * <p>
	 * default=true
	 */
	public void setShouldCloseStream(boolean shouldCloseStream) {
		this.shouldCloseStream = shouldCloseStream;
	}

	/**
	 * Deflater strategy: one of Deflater.FILTERED Deflater.HUFFMAN_ONLY
	 * Deflater.DEFAULT_STRATEGY
	 * <p>
	 * Default: Deflater.FILTERED . This should be changed very rarely.
	 */
	public void setDeflaterStrategy(int deflaterStrategy) {
		this.deflaterStrategy = deflaterStrategy;
	}

	public void writeRow(IImageLine imgline) {
		writeRow(imgline, rowNum + 1);
	}

	public void writeRow(IImageLine imgline, int rownumber) {
		rowNum++;
		if (rownumber >= 0 && rowNum != rownumber)
			throw new PngjOutputException("rows must be written in order: expected:" + rowNum + " passed:" + rownumber);
		if (datStream == null)
			init();
		// swap
		byte[] tmp = rowb;
		rowb = rowbprev;
		rowbprev = tmp;
		imgline.toPngRaw(rowb);
		filterAndSend();
	}

	public void writeRowInt(int[] buf) {
		writeRow(new ImageLine(imgInfo, buf));
	}

}