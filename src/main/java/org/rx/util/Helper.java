package org.rx.util;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.rx.core.Contract;
import org.rx.core.Strings;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

import static org.rx.core.Contract.require;

@Slf4j
public class Helper {
    @SneakyThrows
    public static <T> String convertToXml(T obj) {
        require(obj);

        JAXBContext jaxbContext = JAXBContext.newInstance(obj.getClass());
        Marshaller marshaller = jaxbContext.createMarshaller();
        //marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true); // pretty
        //marshaller.setProperty(Marshaller.JAXB_ENCODING, "ISO-8859-1"); // specify encoding
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        marshaller.marshal(obj, stream);
        return stream.toString(Contract.Utf8);
    }

    @SneakyThrows
    public static <T> T convertFromXml(String xml, Class<T> type) {
        require(xml, type);

        JAXBContext jaxbContext = JAXBContext.newInstance(type);
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        byte[] data = xml.getBytes(Contract.Utf8);
        ByteArrayInputStream stream = new ByteArrayInputStream(data);
        return (T) unmarshaller.unmarshal(stream);
    }

    public static String zip(String sourcePath, String password) throws ZipException {
        require(sourcePath);

        String baseFileName = FilenameUtils.getBaseName(sourcePath);
        String zipFilePath = baseFileName + ".zip";
        zip(sourcePath, zipFilePath, password);
        return zipFilePath;
    }

    public static void zip(String sourcePath, String zipFilePath, String password) throws ZipException {
        require(sourcePath, zipFilePath);

        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
        zipParameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
        if (!Strings.isEmpty(password)) {
            zipParameters.setEncryptFiles(true);
            zipParameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
            zipParameters.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
            zipParameters.setPassword(password);
        }
        ZipFile zipFile = new ZipFile(zipFilePath);
        File source = new File(sourcePath);
        if (source.isDirectory()) {
            zipFile.addFolder(source, zipParameters);
        } else {
            zipFile.addFile(source, zipParameters);
        }
    }

    public static void unzip(String destPath) throws ZipException {
        String baseFileName = FilenameUtils.getBaseName(destPath);
        String zipFilePath = baseFileName + ".zip";
        unzip(destPath, zipFilePath, null);
    }

    public static void unzip(String destPath, String zipFilePath, String password) throws ZipException {
        ZipFile zipFile = new ZipFile(zipFilePath);
        if (zipFile.isEncrypted()) {
            zipFile.setPassword(password);
        }
        zipFile.extractAll(destPath);
    }

    @SneakyThrows
    public static Map<String, List<Object[]>> readExcel(InputStream in, boolean skipColumn) {
        return readExcel(in, skipColumn, false);
    }

    @SneakyThrows
    public static Map<String, List<Object[]>> readExcel(InputStream in, boolean skipColumn, boolean keepNullRow) {
        Map<String, List<Object[]>> data = new LinkedHashMap<>();
        try (HSSFWorkbook workbook = new HSSFWorkbook(in)) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                List<Object[]> rows = new ArrayList<>();
                HSSFSheet sheet = workbook.getSheetAt(sheetIndex);
                for (int rowIndex = skipColumn ? 1 : sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    HSSFRow row = sheet.getRow(rowIndex);
                    if (row == null) {
                        if (keepNullRow) {
                            rows.add(null);
                        }
                        continue;
                    }
                    List<Object> cells = new ArrayList<>();
                    for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
                        HSSFCell cell = row.getCell(i);
                        if (cell == null) {
                            cells.add(null);
                            continue;
                        }
                        Object value;
                        switch (cell.getCellTypeEnum()) {
                            case NUMERIC:
                                value = cell.getNumericCellValue();
                                break;
                            case BOOLEAN:
                                value = cell.getBooleanCellValue();
                                break;
                            default:
                                value = cell.getStringCellValue();
                                break;
                        }
                        cells.add(value);
                    }
                    if (cells.contains(null)) {
                        log.debug(String.format("current=%s offset=%s count=%s -> %s/%s", JSON.toJSONString(cells),
                                row.getFirstCellNum(), row.getLastCellNum(), rowIndex, sheetIndex));
                    }
                    rows.add(cells.toArray());
                }
                data.put(sheet.getSheetName(), rows);
            }
        }
        return data;
    }

    public static void writeExcel(OutputStream out, Map<String, List<Object[]>> data) {
        writeExcel(out, data, null);
    }

    @SneakyThrows
    public static void writeExcel(OutputStream out, Map<String, List<Object[]>> data, Function<HSSFRow, HSSFRow> onRow) {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            for (Map.Entry<String, List<Object[]>> entry : data.entrySet()) {
                HSSFSheet sheet = workbook.getSheet(entry.getKey());
                if (sheet == null) {
                    sheet = workbook.createSheet(entry.getKey());
                }
                List<Object[]> rows = entry.getValue();
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    HSSFRow row = sheet.getRow(rowIndex);
                    if (row == null) {
                        row = sheet.createRow(rowIndex);
                    }
                    Object[] cells = rows.get(rowIndex);
                    for (int i = 0; i < cells.length; i++) {
                        HSSFCell cell = row.getCell(i);
                        if (cell == null) {
                            cell = row.createCell(i);
                        }
                        Object val = cells[i];
                        if (val == null) {
                            continue;
                        }
                        String value;
                        if (val instanceof Date) {
                            value = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(val);
                        } else {
                            value = String.valueOf(val);
                        }
                        cell.setCellValue(value);
                    }
                    if (onRow != null) {
                        if (row.getRowStyle() == null) {
                            row.setRowStyle(workbook.createCellStyle());
                        }
                        onRow.apply(row);
                    }
                }
            }
            workbook.write(out);
        }
    }
}
