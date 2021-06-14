package com.graphhopper.reader.dem;

import com.graphhopper.util.Downloader;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.cts.CRSFactory;
import org.cts.IllegalCoordinateException;
import org.cts.crs.CoordinateReferenceSystem;
import org.cts.crs.GeodeticCRS;
import org.cts.op.CoordinateOperation;
import org.cts.op.CoordinateOperationException;
import org.cts.op.CoordinateOperationFactory;
import org.cts.registry.EPSGRegistry;
import org.cts.registry.RegistryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class DGMProvider extends AbstractElevationProvider {

    private final double MIN_LAT;
    private final double MIN_LON;
    private final double MAX_LAT;
    private final double MAX_LON;

    private static final String RESOLUTION = "25"; // Resolution in Meter available Options are 1 | 10 | 25
    
    private final CoordinateTransformation transformation;
    
    private final HashMap<String, Map<String, Double>> loadedEleData;

    public DGMProvider() {
        this("");
    }

    public DGMProvider(String cacheDir) {
        super(cacheDir.isEmpty() ? "/tmp/dgm" : cacheDir);

        this.MIN_LAT = 53.369689;
        this.MIN_LON = 9.693330;
        this.MAX_LAT = 53.759930;
        this.MAX_LON = 10.345204;

        this.downloader = new Downloader("Graphopper DGMReader").setTimeout(10000);
        this.transformation = new CoordinateTransformation("EPSG:4326", "EPSG:25832");
        this.loadedEleData = new HashMap<>();

        prepareElevationData();
    }

    public static void main(String[] args) {
        DGMProvider provider = new DGMProvider();
        double ele = provider.getEle(53.57426342628851,9.946746826171877);
        System.out.println("Elevation for 53.55 / 9.99 is " + ele);
    }

    /**
     * 1) Download Data
     * 2) Convert Into GPS Coordinate System
     * 3) Store in /tmp/dgm in 1kmx1km sized files
     */
    public void prepareElevationData() {
        String zipFilePath = cacheDir + "/HH_Elevation.zip";
        String unzippedDirPath = cacheDir.getAbsolutePath();

        cacheDir.mkdirs();
        downloadElevationData(zipFilePath);

        new File(unzippedDirPath).mkdirs();
        unzipFile(zipFilePath, unzippedDirPath);

    }

    private void downloadElevationData(String destFilePath) {
        File file = new File(destFilePath);
        try {
            System.out.println("Dowloading elevation data");
            downloadFile(file, getDownloadURL(0, 0));
            System.out.println("Finished downloading elevation data");
        } catch (IOException e) {
            logger.warn("cannot load file from " + getDownloadURL(0, 0) + ", error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void unzipFile(String zipFilePath, String destFilePath) {
        try {
            System.out.println("Unzipping elevation data");
            ZipFile zipFile = new ZipFile(zipFilePath);
            zipFile.extractAll(destFilePath);
            System.out.println("Finished unzipping elevation data");
        } catch (ZipException e) {
            e.printStackTrace();
        }
    }

    /**
     * retrieve the file path containing the coordinate
     * @param lat - latitude in EPSG:25832
     * @param lon - longitude in EPSG:25832
     * @return absolute filepath
     */
    @Override
    String getFileName(double lat, double lon) {
        String path = getEleDataFolderPath();
        String latPart = Double.toString(lat).substring(0, 3);
        String lonPart = Double.toString(lon).substring(0, 4);
        String folderName = "s32_" + latPart;
        String fileName = "dgm" + RESOLUTION + "_32_" + latPart + "_" + lonPart + "_1_hh.xyz";
        return path + "/" + folderName + "/" + fileName;
    }

    /**
     * naming follows no convention and is therefore hardcoded for each available resolution
     */
    private String getEleDataFolderPath() {
        String basePath = cacheDir.getAbsolutePath();
        switch (RESOLUTION) {
            case "1":
                return basePath + "/dgm1_hh_2020-03-29";
            case "10":
                return basePath + "/dgm10_hh_2020";
            case "25":
                return basePath + "/dgm25_hh_2000";
            default:
                logger.warn("Unknown RESOLUTION: " + RESOLUTION);
                return null;
        }
    }

    /**
     * For Hamburg's Elevation Data there is only one URL, independent of current lat, long
     * alternative resolutions are: DGM1 (1m), DGM10 (10m), DGM25 (25m)
     */
    @Override
    String getDownloadURL(double lat, double lon) {
        String resolutionInMeter = RESOLUTION;
        return "https://daten-hamburg.de/geographie_geologie_geobasisdaten/Digitales_Hoehenmodell/DGM"
                + resolutionInMeter
                + "/dgm"
                + resolutionInMeter
                + "_2x2km_XYZ_hh_2021_04_01.zip";
    }

    /**
     * Download a file at the provided url and save it as the given downloadFile if the downloadFile does not exist.
     */
    private void downloadFile(File downloadFile, String url) throws IOException {
        if (!downloadFile.exists()) {
            int max = 3;
            for (int trial = 0; trial < max; trial++) {
                try {
                    downloader.downloadFile(url, downloadFile.getAbsolutePath());
                } catch (SocketTimeoutException ex) {
                    if (trial >= max - 1)
                        throw new RuntimeException(ex);
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } else {
            logger.info("File already exists: " + getDownloadURL(0, 0));
        }
    }

    @Override
    public double getEle(double latEPSG4326, double lonEPSG4326) {
        if (latEPSG4326 == 53.57426342628851 && lonEPSG4326 == 9.946746826171877) {
            int tz = 0;
        }

        // return directly if we know that the given coordinate is not included
        if (latEPSG4326 < this.MIN_LAT || latEPSG4326 > this.MAX_LAT
                || lonEPSG4326 < this.MIN_LON || lonEPSG4326 > this.MAX_LON) {
            return 0;
        }

        // elevation data is only available as EPSG25832 but given as EPSG4326
        double[] coordEPSG25832;
        try {
            coordEPSG25832 = transformation.transformCoordinate(latEPSG4326, lonEPSG4326);
        } catch (IllegalCoordinateException | CoordinateOperationException e) {
            e.printStackTrace();
            return 0;
        }

        int latEPSG25832 = Math.toIntExact(Math.round(coordEPSG25832[0]));
        int lonEPSG25832 = Math.toIntExact(Math.round(coordEPSG25832[1]));
        int[] coord = {latEPSG25832, lonEPSG25832};

        return bilinearInterpolatedElevation(latEPSG25832, lonEPSG25832);
    }

    /**
     * bilinear interpolation seems to be a good approach https://www.omnicalculator.com/math/bilinear-interpolation
     *
     * @param lat
     * @param lon
     * @return
     */
    private double bilinearInterpolatedElevation(double lat, double lon) {
        int resolution = Integer.parseInt(RESOLUTION);

        double x = lat;
        double y = lon;

        double x1 = resolution * (Math.floor((float) lat / resolution));
        double x2 = resolution * (Math.ceil((float) lat / resolution));
        double y1 = resolution * (Math.floor((float) lon / resolution));
        double y2 = resolution * (Math.ceil( (float) lon / resolution));

        double q11 = elevation((int) x1, (int) y1);
        double q21 = elevation((int) x2, (int) y1);
        double q12 = elevation((int) x1, (int) y2);
        double q22 = elevation((int) x2, (int) y2);

        // if the given coordinate matches exactly on a coordinate with known elevation return it directly,
        // also since the bilinear interpolation wouldn't work in such a case
        double elevation = -2;
        if (x == x1 && y == y1) {
            elevation = q11;
        } else if (x == x1 && y == y2) {
            elevation = q12;
        } else if (x == x2 && y == y1) {
                elevation = q21;
        } else if (x == x2 && y == y2) {
            elevation = q22;
        } else if (x1 == x2) {
            elevation = q11 + (y - y1) * ((q12 - q11) / (y2 - y1));
        } else if (y1 == y2) {
            elevation = q11 + (x - x1) * ((q12 - q11) / (x2 - x1));
        } else {
            double r1 = (x2 - x) / (x2 - x1) * q11 + (x - x1) / (x2 - x1) * q21;
            double r2 = (x2 - x) / (x2 - x1) * q12 + (x - x1) / (x2 - x1) * q22;
            double p = (y2 - y) / (y2 - y1) * r1 + (y - y1) / (y2 - y1) * r2;
            elevation = p;
        }

        return elevation;
    }

    /**
     * get the elevation by snapping the given coordinate to the closes coordinate we know the elevation
     * of and returning this value as elevation
     * @param lat - latitude as EPSG25832
     * @param lon - longitude as EPSG25832
     * @return elevation, 0 if unknown
     */
    private double snappedElevation(double lat, double lon) {
        int resolution = Integer.parseInt(RESOLUTION);
        int latRounded = resolution * (Math.round((float) lat / resolution));
        int lonRounded = resolution * (Math.round((float) lon / resolution));

        return elevation(latRounded, lonRounded);
    }

    private double elevation(int lat, int lon) {
        String key = String.valueOf(lat).substring(0, 3) + "_" + String.valueOf(lon).substring(0, 4);

        Map<String, Double> bucket = getBucket(key, lat, lon);
        if (bucket == null) {
            return 0;
        }

        String coordStr = lat + ".00_" + lon + ".00";
        Double elevation = bucket.get(coordStr);
        if (elevation == null) {
            return 0;
        } else {
            return elevation;
        }
    }

    private Map<String, Double> getBucket(String key, double latEPSG25832, double lonEPSG25832) {
        // load data from file if not in memory yet
        if (!loadedEleData.containsKey(key)) {
            logger.info("loading data from file with key: " + key + " files currently loaded: " + loadedEleData.size());
            String filePath = getFileName(latEPSG25832, lonEPSG25832);
            if (new File(filePath).exists()) {
                Map<String, Double> data = readXYZFile(filePath);
                loadedEleData.put(key, data);
            } else {
                // there is no file for this area and therefore no elevation data available,
                // to reduce io operations a null bucket for this key is added
                loadedEleData.put(key, null);
            }
        }
        return loadedEleData.get(key);
    }

    private Map<String, Double> readXYZFile(String filePath) {
        HashMap<String, Double> data = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            for (String line; (line = br.readLine()) != null; ) {
                String[] splitted_line = line.split(" ");
                // increment by one equals 1m which also is the highest available resolution
                int lat_EPSG_25832 = (int) Double.parseDouble(splitted_line[0]);
                int lon_EPSG_25832 = (int) Double.parseDouble(splitted_line[1]);
                double elevation = Double.parseDouble(splitted_line[2]);

                int[] coordinate = {lat_EPSG_25832, lon_EPSG_25832};
                String coordStr = splitted_line[0] + "_" + splitted_line[1];
                data.put(coordStr, elevation);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return data;
    }

    @Override
    public void release() {
        loadedEleData.clear();
    }
}

/**
 * Helper class to project coordinates between different coordinate systems
 *
 * to add new projections the setUsedCoordOperation() method needs to be extended since there is no uniform way
 * to find the correct projection method for all possible projections
 */
class CoordinateTransformation {

    private final String trgtCoordSystem;
    private final String srcCoordSystem;

    private Set<CoordinateOperation> coordOps;
    private CoordinateOperation coordOp;

    public CoordinateTransformation() {
        this("EPSG:25832", "EPSG:4326");
    }

    public CoordinateTransformation(String srcCoordSystem, String trgtCoordSystem) {
        this.srcCoordSystem = srcCoordSystem;
        this.trgtCoordSystem = trgtCoordSystem;

        CRSFactory cRSFactory = new CRSFactory();
        RegistryManager registryManager = cRSFactory.getRegistryManager();
        registryManager.addRegistry(new EPSGRegistry());
        CoordinateReferenceSystem crsSrc = null;
        try {
            crsSrc = cRSFactory.getCRS(srcCoordSystem);
            CoordinateReferenceSystem crsTrgt = cRSFactory.getCRS(trgtCoordSystem);
            this.coordOps = CoordinateOperationFactory.createCoordinateOperations((GeodeticCRS) crsSrc, (GeodeticCRS) crsTrgt);
            setUsedCoordOperation();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * there may be multiple transformations available, but only one returns the correct results
     * the only way I found is to test a transformation where the result is known and select the
     * correct transformation accordingly
     */
    private void setUsedCoordOperation() throws Exception {
        // set used coord system in case we transform from EPSG:25832 to EPSG:4326
        // this does not apply to other transformations!
        if (srcCoordSystem.equals("EPSG:25832") && trgtCoordSystem.equals("EPSG:4326")) {
            double[] coord = {551120.00, 5930000.00};
            for (CoordinateOperation op : coordOps) {
                try {
                    double[] dd = op.transform(coord);
                    if (dd[0] > 9.770 && dd[0] < 9.771) {
                        coordOp = op;
                        return;
                    }
                } catch (IllegalCoordinateException | CoordinateOperationException e) {
                    e.printStackTrace();
                }
            }
        } else if (srcCoordSystem.equals("EPSG:4326") && trgtCoordSystem.equals("EPSG:25832")) {
            double[] coord = {9.770968020290818, 53.51644661059623};
            for (CoordinateOperation op : coordOps) {
                try {
                    double[] dd = op.transform(coord);
                    if (dd[0] > 551100 && dd[0] < 551200) { // {551120.00, 5930000.00}
                        coordOp = op;
                        return;
                    }
                } catch (IllegalCoordinateException | CoordinateOperationException e) {
                    e.printStackTrace();
                }
            }
        } else {
            throw new Exception("Transformation could not be selected! Please check which projection " +
                    "should be applied for the used coordinate systems");
        }
    }

    public double[] transformCoordinate(double lat, double lon)
            throws IllegalCoordinateException, CoordinateOperationException {
        double[] coord = {lon, lat};
        return coordOp.transform(coord);
    }
}