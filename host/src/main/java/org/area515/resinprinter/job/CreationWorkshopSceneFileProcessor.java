package org.area515.resinprinter.job;

import java.io.BufferedWriter;
import java.io.FileWriter;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import com.jcraft.jsch.Buffer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.area515.resinprinter.exception.SliceHandlingException;
import org.area515.resinprinter.job.AbstractPrintFileProcessor.DataAid;
import org.area515.resinprinter.job.render.RenderedData;
import org.area515.resinprinter.notification.NotificationManager;
import org.area515.resinprinter.printer.Printer;
import org.area515.resinprinter.server.HostProperties;
import org.area515.resinprinter.twodim.SimpleImageRenderer;
import org.area515.util.IOUtilities;

import se.sawano.java.text.AlphanumericComparator;

public class CreationWorkshopSceneFileProcessor extends AbstractPrintFileProcessor<Object, Object>
		implements Previewable {
	private static final Logger logger = LogManager.getLogger();
	private CreationWorkshopImageCache imageCache = null;

	@Override
	public String[] getFileExtensions() {
		return new String[] { "cws", "zip" };
	}

	@Override
	public boolean acceptsFile(File processingFile) {
		// TODO: we shouldn't except all zip files only those that have embedded
		// gif/jpg/png information.
		if (processingFile.getName().toLowerCase().endsWith(".zip")
				|| processingFile.getName().toLowerCase().endsWith(".cws")) {
			if (zipHasGCode(processingFile)) {
				// if the zip has gcode, treat it as a CW scene
				logger.info("Accepting new printable {} as a {}", processingFile.getName(), this.getFriendlyName());
				return true;
			}
		}
		return false;
	}

	protected SortedMap<String, File> findImages(File jobFile) throws JobManagerException {
		String[] extensions = { "png", "PNG" };
		boolean recursive = true;

		Collection<File> files = FileUtils.listFiles(buildExtractionDirectory(jobFile.getName()),
				extensions, recursive);

		TreeMap<String, File> images = new TreeMap<>(new AlphanumericComparator());

		for (File file : files) {
			images.put(file.getName(), file);
		}

		return images;
	}

	@Override
	public BufferedImage renderPreviewImage(DataAid dataAid) throws SliceHandlingException {
		try {
			prepareEnvironment(dataAid.printJob.getJobFile(), dataAid.printJob);

			SortedMap<String, File> imageFiles = findImages(dataAid.printJob.getJobFile());

			dataAid.printJob.setTotalSlices(imageFiles.size());
			Iterator<File> imgIter = imageFiles.values().iterator();

			// Preload first image then loop
			int sliceIndex = dataAid.customizer.getNextSlice();
			while (imgIter.hasNext() && sliceIndex > 0) {
				sliceIndex--;
				imgIter.next();
			}

			if (!imgIter.hasNext()) {
				throw new IOException("No Image Found for index:" + dataAid.customizer.getNextSlice());
			}
			File imageFile = imgIter.next();

			SimpleImageRenderer renderer = new SimpleImageRenderer(dataAid, this, imageFile);
			RenderedData stdImage = renderer.call();
			return stdImage.getPrintableImage();
		} catch (IOException | JobManagerException e) {
			throw new SliceHandlingException(e);
		}
	}

	@Override
	public Double getBuildAreaMM(PrintJob processingFile) {
		return null;
	}

	@Override
	public JobStatus processFile(final PrintJob printJob) throws Exception {
		File gCodeFile = findGcodeFile(printJob.getJobFile());
		String FilepathAsString = gCodeFile.getAbsolutePath();
		String FilePath = FilenameUtils.getPath(FilepathAsString);
		DataAid aid = initializeJobCacheWithDataAid(printJob);

		int numberOfBottomLayers = 0;
		int defaultLayerExposureTime = 0;
		int defaultBottomLayerExposureTime = 0;
		int defaultLayerExposureWarmUpTime = 0;
		Integer layerExposureTimeOverride = null;
		Integer layerExposureWarmupTimeOverride = null;

		Pattern slicePattern = Pattern.compile("\\s*;\\s*<\\s*Slice\\s*>\\s*(\\d+|blank)\\s*",
				Pattern.CASE_INSENSITIVE);
		Pattern liftSpeedPattern = Pattern.compile(
				"\\s*;\\s*\\(?\\s*Z\\s*Lift\\s*Feed\\s*Rate\\s*=\\s*([\\d\\.]+)\\s*(?:[Mm]{2}?/[Ss])?\\s*\\)?\\s*",
				Pattern.CASE_INSENSITIVE);
		Pattern liftDistancePattern = Pattern.compile(
				"\\s*;\\s*\\(?\\s*Lift\\s*Distance\\s*=\\s*([\\d\\.]+)\\s*(?:[Mm]{2})?\\s*\\)?\\s*",
				Pattern.CASE_INSENSITIVE);
		Pattern sliceCountPattern = Pattern.compile("\\s*;\\s*Number\\s*of\\s*Slices\\s*=\\s*(\\d+)\\s*",
				Pattern.CASE_INSENSITIVE);
		Pattern bottomDelay = Pattern.compile(
				"\\s*;\\s*\\(?\\s*Bottom\\s*Layers\\s*Time\\s*=\\s*([\\d\\.]+)\\s*(?:ms)?\\s*\\)?\\s*",
				Pattern.CASE_INSENSITIVE);
		Pattern exposureDelay = Pattern.compile(
				"\\s*;\\s*\\(?\\s*Layer\\s*Time\\s*=\\s*([\\d\\.]+)\\s*(?:ms)?\\s*\\)?\\s*", Pattern.CASE_INSENSITIVE);
		Pattern bottomLayerNumber = Pattern.compile(
				"\\s*;\\s*\\(?\\s*Number\\s*of\\s*Bottom\\s*Layers\\s*=\\s*([\\d\\.]+)\\s*\\)?\\s*",
				Pattern.CASE_INSENSITIVE);
		Pattern sliceParameters = Pattern.compile("\\s*;\\s*<\\s*NextSliceExposure\\s*>(\\s*[a-z]=\\d+\\s*)+",
				Pattern.CASE_INSENSITIVE);

		Printer printer = printJob.getPrinter();
		String printerName = printer.getName();

		BufferedReader stream = null;
		long startOfLastImageDisplay = -1;
		try {
			logger.info("Parsing file:{}", gCodeFile);

			stream = new BufferedReader(new FileReader(gCodeFile));
			String currentLine;
			Integer sliceCount = null;

			// Transform unary operator on buffered image, to pass to cache thread.
			UnaryOperator<BufferedImage> imageTransformOp = image -> {
				BufferedImage transformedImage = null;
				try {
					RenderedData data = aid.cache.getOrCreateIfMissing(Boolean.TRUE); // ?
					transformedImage = applyImageTransforms(aid, data.getScriptEngine(), image);
				} catch (Exception e) {
					transformedImage = null;
				}
				return transformedImage;
			};
			// Image cache object, automatically pre-loading and transforming images.
			String baseFilename = FilenameUtils.removeExtension(gCodeFile.getName());
			int padLength = determinePadLength(gCodeFile);
			imageCache = new CreationWorkshopImageCache(gCodeFile.getParentFile(), baseFilename, padLength,
					imageTransformOp);
			// Start image caching thread.
			imageCache.start();

			// We can't set these values, that means they aren't set to helpful values when
			// this job starts
			// data.printJob.setExposureTime(data.inkConfiguration.getExposureTime());
			// data.printJob.setZLiftDistance(data.slicingProfile.getLiftFeedRate());
			// data.printJob.setZLiftSpeed(data.slicingProfile.getLiftDistance());
			ImageIO.setUseCache(false);

			printerName = printerName.replaceAll("\\s+", "\\%20");
			if (printerName.equals("Photocentric%20Magna")) {
				defaultLayerExposureWarmUpTime = 1700;
			} else if (printerName.equals("Photocentric%20Magna%20V.2")) {
				defaultLayerExposureWarmUpTime = 1700;
			} else if (printerName.equals("LC%20Opus")) {
				defaultLayerExposureWarmUpTime = 200;
			} else if (printerName.equals("LC%20Dental")) {
				defaultLayerExposureWarmUpTime = 200;
			} else if (printerName.equals("LC%20Magna%20Figurine")) {
				defaultLayerExposureWarmUpTime = 200;
			} else if (printerName.equals("LC%20Nano")) {
				defaultLayerExposureWarmUpTime = 0;
			}

			while ((currentLine = stream.readLine()) != null && printer.isPrintActive()) {

				// Slice Parameters
				Matcher matcher = sliceParameters.matcher(currentLine);
				if (matcher.matches()) {
					logger.info("Next Slice Parameters Found: {}", currentLine);
					String[] splittedParams = currentLine.split("\\s+");
					for (String parameter : splittedParams) {
						if (parameter.startsWith("e=")) {
							layerExposureTimeOverride = Integer.parseInt(parameter.substring(2));
							logger.info("Current Slice exposure set to: {}", layerExposureTimeOverride);
						} else if (parameter.startsWith("d=")) {
							layerExposureWarmupTimeOverride = Integer.parseInt(parameter.substring(2));
							logger.info("Current Slice LED Warmup set to: {}", layerExposureWarmupTimeOverride);
						}
					}
					continue;
				}

				matcher = slicePattern.matcher(currentLine);
				if (matcher.matches()) {
					if (sliceCount == null) {
						throw new IllegalArgumentException("No 'Number of Slices' line in gcode file");
					}

					if (matcher.group(1).toUpperCase().equals("BLANK")) {
						// This is the perfect time to wait for a pause if one is required.
						printer.waitForPauseIfRequired();
					} else {
						if (startOfLastImageDisplay > -1) {
							// printJob.setCurrentSliceTime(System.currentTimeMillis() -
							// startOfLastImageDisplay);
							printJob.addNewSlice(System.currentTimeMillis() - startOfLastImageDisplay, null);
						}
						startOfLastImageDisplay = System.currentTimeMillis();

						RenderedData data = aid.cache.getOrCreateIfMissing(Boolean.TRUE);
						BufferedImage oldImage = data.getPrintableImage();
						Integer sliceIndex = Integer.parseInt(matcher.group(1));
						// printJob.setCurrentSlice(sliceIndex);
						String imageNumber = String.format("%0" + padLength + "d", sliceIndex);
						String imageFilename = FilenameUtils.removeExtension(gCodeFile.getName()) + imageNumber
								+ ".png";

						// logger.info("Load cached picture from file: {}", imageFilename);
						BufferedImage newImage = imageCache.getCachedOrLoadImage(sliceIndex);
						// applyBulbMask(aid, (Graphics2D)newImage.getGraphics(), newImage.getWidth(),
						// newImage.getHeight());
						data.setPrintableImage(newImage);
						// Notify the client that the printJob has increased the currentSlice
						NotificationManager.jobChanged(printer, printJob);
						String slicePath = "/" + FilePath + imageFilename;

						// printer.showImage(data.getPrintableImage(), true);
						// show image arguments list.
						List<String> args = new ArrayList<>(Arrays.asList(
								"nice",
								"-n", "-2",
								"/opt/cwh/os/Linux/armv61/show_image",
								"-d", "5",
								"-p", printerName));

						// Exposure time
						args.add("-e");
						if (layerExposureTimeOverride != null) {
							args.add(Integer.toString(layerExposureTimeOverride));
							logger.info("Overriden Layer Exposure Time: {}", layerExposureTimeOverride);
						} else {
							if (sliceIndex < numberOfBottomLayers) {
								args.add(Integer.toString(defaultBottomLayerExposureTime));
								logger.info("Current Layer Exposure Time (Bottom Layers): {}",
										defaultBottomLayerExposureTime);
							} else {
								args.add(Integer.toString(defaultLayerExposureTime));
								logger.info("Current Layer Exposure Time: {}", defaultLayerExposureTime);
							}
						}

						// warmup time
						args.add("-b");
						if (layerExposureWarmupTimeOverride != null) {
							args.add(Integer.toString(layerExposureWarmupTimeOverride));
							logger.info("Overidden LED Warmup: {}", layerExposureWarmupTimeOverride);
						} else {
							args.add(Integer.toString(defaultLayerExposureWarmUpTime));
							logger.info("LED Warmup: {}", defaultLayerExposureWarmUpTime);
						}

						// mask
						args.add("-m");
						args.add("/home/pi/mask/mask.png");

						// layer image path
						args.add(slicePath);

						logger.info("Display picture on screen: {}", imageFilename);

						// execute show image
						ProcessBuilder pb = new ProcessBuilder(args);
						Process showingSlice = pb.start();
						showingSlice.waitFor();

						// reseting slice params
						layerExposureTimeOverride = null;
						layerExposureWarmupTimeOverride = null;

						if (oldImage != null) {
							oldImage.flush();
						}
					}

					continue;
				}

				// matching gcode params
				matcher = sliceCountPattern.matcher(currentLine);
				if (matcher.matches()) {
					sliceCount = Integer.parseInt(matcher.group(1));
					printJob.setTotalSlices(sliceCount);
					logger.info("Found:{} slices", sliceCount);
					continue;
				}

				matcher = liftSpeedPattern.matcher(currentLine);
				if (matcher.matches()) {
					double foundLiftSpeed = Double.parseDouble(matcher.group(1));
					if (printJob.isZLiftSpeedOverriden()) {
						logger.info("Override: LiftDistance:{} overrided to:{}", String.format("%1.3f", foundLiftSpeed),
								String.format("%1.3f", printJob.getZLiftSpeed()));
					} else {
						printJob.setZLiftSpeed(foundLiftSpeed);
						logger.info("Found: LiftSpeed of:" + String.format("%1.3f", foundLiftSpeed));
					}
					continue;
				}

				matcher = liftDistancePattern.matcher(currentLine);
				if (matcher.matches()) {
					double foundLiftDistance = Double.parseDouble(matcher.group(1));
					if (printJob.isZLiftDistanceOverriden()) {
						logger.info("Override: LiftDistance:{} overrided to:{}",
								String.format("%1.3f", foundLiftDistance),
								String.format("%1.3f", printJob.getZLiftDistance()));
					} else {
						printJob.setZLiftDistance(foundLiftDistance);
						logger.info("Found: LiftDistance of:{}", String.format("%1.3f", foundLiftDistance));
					}
					continue;
				}
				matcher = bottomDelay.matcher(currentLine);
				if (matcher.matches()) {
					Integer foundBottomDelay = Integer.parseInt(matcher.group(1));
					logger.info("Found: Bottom Layer Exposure time:{}", foundBottomDelay);
					defaultBottomLayerExposureTime = foundBottomDelay;
					continue;
				}

				matcher = exposureDelay.matcher(currentLine);
				if (matcher.matches()) {
					Integer foundExposureDelay = Integer.parseInt(matcher.group(1));
					logger.info("Found: Layer Layer Exposure time:{}", foundExposureDelay);
					defaultLayerExposureTime = foundExposureDelay;
					continue;
				}

				matcher = bottomLayerNumber.matcher(currentLine);
				if (matcher.matches()) {
					Integer foundBottomLayers = Integer.parseInt(matcher.group(1));
					logger.info("Found: Number of Bottom Layers:{}", foundBottomLayers);
					numberOfBottomLayers = foundBottomLayers;
					continue;
				}

				// lines to ignore
				if (printerName.equals("LC%20Dental")) {
					if ((currentLine.contains("G4") && currentLine.contains("SLICE Exposure Delay"))
							|| (currentLine.contains("M42 P0 S1") && currentLine.contains("SLICE LED On"))
							|| (currentLine.contains("M42 P0 S0") && currentLine.contains("SLICE LED Off"))) {
						continue;
					}
				} else if (printerName.equals("Photocentric%20Magna")
						|| printerName.equals("Photocentric%20Magna%20V.2")) {
					if (currentLine.contains(";<Delay> 2000")
							|| currentLine.contains(";<Delay> " + defaultLayerExposureTime)
							|| currentLine.contains(";<Delay> " + defaultBottomLayerExposureTime)
							|| (currentLine.contains("M42 P0 S1") && currentLine.contains("SLICE LED on"))
							|| (currentLine.contains("M42 P0 S0") && currentLine.contains("SLICE LED off"))) {
						continue;
					}
				}
				// send gcode
				printer.getGCodeControl().executeGCodeWithTemplating(printJob, currentLine, true);
			}

			return printer.isPrintActive() ? JobStatus.Completed : printer.getStatus();
		} catch (IOException e) {
			logger.error("Error occurred while processing file.", e);
			throw e;
		} finally {
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
				}
			}
			if (imageCache != null) {
				imageCache.close();
			}
			aid.cache.clearCache(Boolean.TRUE);
			clearDataAid(printJob);
		}
	}

	public static File buildExtractionDirectory(String archive) {
		return Paths.get(HostProperties.Instance().getWorkingDir().toString(), archive).toFile();
	}

	private void deleteDirectory(File extractDirectory) throws JobManagerException {
		String unable = "Unable to delete directory (.*)[.]";
		boolean deletePerformed = false;
		int attemptsToDelete = 0;
		List<IOException> cantDelete = new ArrayList<>();
		do {
			try {
				attemptsToDelete++;
				FileUtils.deleteDirectory(extractDirectory);
				deletePerformed = true;
			} catch (IOException e) {
				if (e.getMessage() != null) {
					Pattern pattern = Pattern.compile(unable);
					Matcher matcher = pattern.matcher(e.getMessage());
					if (matcher.matches()) {
						logger.debug(() -> {
							String[] output = IOUtilities
									.executeNativeCommand(new String[] { "ls", "-al", matcher.group(1) }, null);
							StringBuilder builder = new StringBuilder();
							for (String outLine : output) {
								builder.append(outLine + "\n");
							}
							return builder.toString();
						});
					}
				}
				cantDelete.add(e);
				deletePerformed = false;
			}
		} while (!deletePerformed && attemptsToDelete < 3);

		if (!deletePerformed) {
			if (cantDelete.size() > 1) {
				for (IOException e : cantDelete) {
					logger.error("Error List", e);
				}
			}
			throw new JobManagerException("Couldn't clean directory for new job:" + extractDirectory,
					cantDelete.get(0));
		}
	}

	@Override
	public void prepareEnvironment(File processingFile, PrintJob printJob) throws JobManagerException {
		List<PrintJob> printJobs = PrintJobManager.Instance().getJobsByFilename(processingFile.getName());
		for (PrintJob currentJob : printJobs) {
			if (!currentJob.getId().equals(printJob.getId()) && currentJob.isPrintInProgress()) {
				throw new JobManagerException(
						"It currently isn't possible to print more than 1 " + getFriendlyName() + " file at once.");
			}
		}

		File extractDirectory = buildExtractionDirectory(processingFile.getName());
		if (extractDirectory.exists()) {
			deleteDirectory(extractDirectory);
		}

		try {
			unpackDir(processingFile);
		} catch (IOException e) {
			throw new JobManagerException(
					"Couldn't unpack new job:" + processingFile + " into working directory:" + extractDirectory, e);
		}
	}

	@Override
	public void cleanupEnvironment(File processingFile) throws JobManagerException {
		File extractDirectory = buildExtractionDirectory(processingFile.getName());
		if (extractDirectory.exists()) {
			deleteDirectory(extractDirectory);
		}
	}

	protected boolean zipHasGCode(File zipFile) {
		ZipFile zip = null;

		try {
			zip = new ZipFile(zipFile, Charset.forName("CP437"));
			return zip.stream().anyMatch(z -> z.getName().toLowerCase().endsWith("gcode"));
		} catch (IOException e) {
			logger.error("Unable to open uploaded zip file", e);
		} finally {
			if (zip != null) {
				try {
					zip.close();
				} catch (IOException e) {
					logger.warn("Unable to close uploaded zip file", e);
				}
			}
		}

		return false;

	}

	private File findGcodeFile(File jobFile) throws JobManagerException {

		String[] extensions = { "gcode" };
		boolean recursive = true;

		//
		// Finds files within a root directory and optionally its
		// subdirectories which match an array of extensions. When the
		// extensions is null all files will be returned.
		//
		// This method will returns matched file as java.io.File
		//
		List<File> files = new ArrayList<File>(
				FileUtils.listFiles(buildExtractionDirectory(jobFile.getName()), extensions, recursive));

		if (files.size() > 1) {
			throw new JobManagerException("More than one gcode file exists in print directory");
		} else if (files.size() == 0) {
			throw new JobManagerException(
					"Gcode file was not found. Did you include the Gcode when you exported your scene?");
		}

		return files.get(0);
	}

	private void unpackDir(File jobFile) throws IOException, JobManagerException {
		ZipFile zipFile = null;
		InputStream in = null;
		OutputStream out = null;
		File extractDirectory = buildExtractionDirectory(jobFile.getName());
		try {
			zipFile = new ZipFile(jobFile, Charset.forName("CP437"));
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				File entryDestination = new File(extractDirectory, entry.getName());
				entryDestination.getParentFile().mkdirs();
				if (entry.isDirectory())
					entryDestination.mkdirs();
				else {
					in = zipFile.getInputStream(entry);
					out = new FileOutputStream(entryDestination);
					IOUtils.copy(in, out);
					IOUtils.closeQuietly(in);
					IOUtils.closeQuietly(out);
				}
			}
			String basename = FilenameUtils.removeExtension(jobFile.getName());
			logger.info("BaseName: {}", FilenameUtils.removeExtension(basename));
			// findGcodeFile(jobFile);
		} finally {
			zipFile.close();
		}
	}

	public int determinePadLength(File gCode) throws FileNotFoundException {
		File currentFile = null;
		for (int t = 1; t < 10; t++) {
			currentFile = new File(gCode.getParentFile(),
					FilenameUtils.removeExtension(gCode.getName()) + String.format("%0" + t + "d", 0) + ".png");
			if (currentFile.exists()) {
				return t;
			}
		}

		throw new FileNotFoundException("Couldn't find any files to determine image index pad.");
	}

	@Override
	public Object getGeometry(PrintJob printJob) throws JobManagerException {
		throw new JobManagerException("You can't get geometry from this type of file");
	}

	@Override
	public Object getErrors(PrintJob printJob) throws JobManagerException {
		throw new JobManagerException("You can't get error geometry from this type of file");
	}

	@Override
	public String getFriendlyName() {
		return "Creation Workshop Scene";
	}

	@Override
	public boolean isThreeDimensionalGeometryAvailable() {
		return false;
	}
}
