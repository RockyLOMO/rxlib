package org.rx.util;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.rx.Logger;
import org.rx.SystemException;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.rx.App.UTF8;
import static org.rx.Contract.require;
import static org.rx.Contract.toJsonString;

public class Helper {
    public static <T> String convertToXml(T obj) {
        require(obj);

        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(obj.getClass());
            Marshaller marshaller = jaxbContext.createMarshaller();
            //marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true); // pretty
            //marshaller.setProperty(Marshaller.JAXB_ENCODING, "ISO-8859-1"); // specify encoding
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            marshaller.marshal(obj, stream);
            return stream.toString(UTF8);
        } catch (Exception ex) {
            throw SystemException.wrap(ex);
        }
    }

    public static <T> T convertFromXml(String xml, Class<T> type) {
        require(xml, type);

        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(type);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            byte[] data = xml.getBytes(UTF8);
            ByteArrayInputStream stream = new ByteArrayInputStream(data);
            return (T) unmarshaller.unmarshal(stream);
        } catch (Exception ex) {
            throw SystemException.wrap(ex);
        }
    }

    public static List<Object[]> readExcel(String filePath, boolean skipColumn) throws IOException {
        List<Object[]> result = new ArrayList<>();
        HSSFWorkbook workbook = new HSSFWorkbook(new FileInputStream(filePath));
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            HSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            for (int rowIndex = skipColumn ? 1 : sheet.getFirstRowNum(); rowIndex < sheet.getLastRowNum(); rowIndex++) {
                HSSFRow row = sheet.getRow(rowIndex);
                List<Object> cellValues = new ArrayList<>();
                for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
                    HSSFCell cell = row.getCell(i);
                    if (cell == null) {
                        cellValues.add(null);
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
                    cellValues.add(value);
                }
                if (cellValues.contains(null)) {
                    Logger.debug(String.format("current=%s offset=%s count=%s -> %s/%s", toJsonString(cellValues),
                            row.getFirstCellNum(), row.getLastCellNum(), rowIndex, sheetIndex));
                }
                result.add(cellValues.toArray());
            }
        }
        return result;
    }
}
