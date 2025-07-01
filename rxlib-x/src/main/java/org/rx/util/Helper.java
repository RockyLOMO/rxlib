package org.rx.util;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.rx.core.Numbers;
import org.rx.exception.InvalidException;
import org.rx.spring.MiddlewareConfig;
import org.rx.spring.SpringContext;
import org.rx.util.function.QuadraFunc;
import org.rx.util.function.TripleFunc;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

import static org.rx.core.Extends.eq;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class Helper {
    public static void sendEmail(String body) {
        MiddlewareConfig config = SpringContext.getBean(MiddlewareConfig.class);
        Helper.sendEmail(body, config.getSmtpPwd(), config.getSmtpTo());
    }

    public static void sendEmail(String body, @NonNull String password, @NonNull String toEmail) {
        final String fromEmail = "17091916400@163.com";
        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.163.com");
        props.put("mail.smtp.port", "25");
        props.put("mail.smtp.auth", "true");
        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(fromEmail, password);
            }
        });

        try {
            MimeMessage msg = new MimeMessage(session);
            msg.addHeader("Content-type", "text/HTML; charset=UTF-8");
            msg.addHeader("format", "flowed");
            msg.addHeader("Content-Transfer-Encoding", "8bit");

            msg.setFrom(new InternetAddress(fromEmail, "System"));
            msg.setReplyTo(InternetAddress.parse("no_reply@x.cn", false));

            msg.setSubject("Notification", "UTF-8");
            msg.setText(body, "UTF-8");
            msg.setSentDate(new Date());

            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
            Transport.send(msg);
        } catch (Exception e) {
            log.warn("sendEmail {}", e.getMessage());
        }
    }

    public static void replaceExcel(InputStream in, OutputStream out, String sheetName, TripleFunc<Integer, List<Object>, List<Object>> fn, boolean is2003File) {
        replaceExcel(in, out, sheetName, fn, is2003File, false);
    }

    @SneakyThrows
    public static void replaceExcel(@NonNull InputStream in, @NonNull OutputStream out, @NonNull String sheetName,
                                    TripleFunc<Integer, List<Object>, List<Object>> fn, boolean is2003File, boolean skipColumn) {
        FormulaEvaluator evaluator = null;
        try (Workbook workbook = is2003File ? new HSSFWorkbook(in) : new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new InvalidException("Sheet {} not found", sheetName);
            }

            List<Object> cells = new ArrayList<>();
            int firstRowNum = sheet.getFirstRowNum();
            for (int rowIdx = skipColumn ? firstRowNum + 1 : firstRowNum; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) {
                    throw new InvalidException("Sheet row {} not found", rowIdx);
//                    fn.accept(rowIndex, rowIndex, Collections.emptyList());
//                    continue;
                }

                cells.clear();
                short firstCellNum = row.getFirstCellNum();
                for (int i = firstCellNum; i < row.getLastCellNum(); i++) {
//                    if (i < firstCellNum) {
//                        cells.add(null);
//                        continue;
//                    }
                    Cell cell = row.getCell(i);
                    if (cell == null) {
                        throw new InvalidException("Sheet row {} cell {} not found", rowIdx, i);
//                        cells.add(null);
//                        continue;
                    }

                    CellType cellType = cell.getCellType();
                    if (cellType == CellType.FORMULA) {
                        if (evaluator == null) {
                            evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                        }
                        cellType = evaluator.evaluateFormulaCell(cell);
                    }
                    Object value;
//                        System.out.println(i + ":" + cellType);
                    switch (cellType) {
                        case NUMERIC:
                            if (!eq(cell.getCellStyle().getDataFormatString(), "General")) {
                                value = cell.getDateCellValue();
                            } else {
                                double n = cell.getNumericCellValue();
                                boolean b = Numbers.hasPrecision(n);
                                if (b) {
                                    value = (int) n;
                                } else {
                                    value = n;
                                }
                                //will auto wrap to double
//                                    value = b ? (int) n : n;
                            }
                            break;
                        case BOOLEAN:
                            value = cell.getBooleanCellValue();
                            break;
                        default:
//                                value = cell.getStringCellValue();
                            if (cell.getCellType() == CellType.ERROR) {
                                cell.setCellType(CellType.STRING);
                            }
                            value = cell.toString();
                            break;
                    }
                    cells.add(value);
                }
                List<Object> newCells = fn.apply(rowIdx, cells);
                if (newCells == null) {
                    sheet.removeRow(row);
                    continue;
                }

                int lastCellNum = firstCellNum + newCells.size();
                for (int i = firstCellNum; i < lastCellNum; i++) {
                    Cell cell = row.getCell(i);
                    if (cell == null) {
                        cell = row.createCell(i);
                    }
                    Object newValue = newCells.get(i - firstRowNum);
                    String value;
                    if (newValue instanceof Date) {
                        value = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(newValue);
                    } else if (newValue == null) {
                        value = null;
                    } else {
                        value = String.valueOf(newValue);
                    }
                    cell.setCellValue(value);
                }
            }
            workbook.write(out);
        }
    }

    public static Map<String, List<Object[]>> readExcel(InputStream in, boolean is2003File) {
        return readExcel(in, is2003File, false, false);
    }

    @SneakyThrows
    public static Map<String, List<Object[]>> readExcel(InputStream in, boolean is2003File, boolean skipColumn, boolean keepNullRow) {
        Map<String, List<Object[]>> data = new LinkedHashMap<>();
        FormulaEvaluator evaluator = null;
        try (Workbook workbook = is2003File ? new HSSFWorkbook(in) : new XSSFWorkbook(in)) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                List<Object[]> rows = new ArrayList<>();
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                for (int rowIndex = skipColumn ? 1 : sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        if (keepNullRow) {
                            rows.add(null);
                        }
                        continue;
                    }
                    List<Object> cells = new ArrayList<>();
                    short firstCellNum = row.getFirstCellNum();
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        if (i < firstCellNum) {
                            cells.add(null);
                            continue;
                        }
                        Cell cell = row.getCell(i);
                        if (cell == null) {
                            cells.add(null);
                            continue;
                        }

                        CellType cellType = cell.getCellType();
                        if (cellType == CellType.FORMULA) {
                            if (evaluator == null) {
                                evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                            }
                            cellType = evaluator.evaluateFormulaCell(cell);
                        }
                        Object value;
//                        System.out.println(i + ":" + cellType);
                        switch (cellType) {
                            case NUMERIC:
                                if (!eq(cell.getCellStyle().getDataFormatString(), "General")) {
                                    value = cell.getDateCellValue();
                                } else {
                                    double n = cell.getNumericCellValue();
                                    boolean b = Numbers.hasPrecision(n);
                                    if (b) {
                                        value = (int) n;
                                    } else {
                                        value = n;
                                    }
                                    //will auto wrap to double
//                                    value = b ? (int) n : n;
                                }
                                break;
                            case BOOLEAN:
                                value = cell.getBooleanCellValue();
                                break;
                            default:
//                                value = cell.getStringCellValue();
                                if (cell.getCellType() == CellType.ERROR) {
                                    cell.setCellType(CellType.STRING);
                                    log.debug("sheetIndex={} rowIndex={} rowCellLength={} cells={}", sheetIndex, rowIndex, row.getLastCellNum(), toJsonString(cells));
                                }
                                value = cell.toString();
                                break;
                        }
                        cells.add(value);
                    }
                    if (cells.contains(null)) {
                        log.debug("sheetIndex={} rowIndex={} rowCellLength={} cells={}", sheetIndex, rowIndex, row.getLastCellNum(), toJsonString(cells));
                    }
                    rows.add(cells.toArray());
                }
                data.put(sheet.getSheetName(), rows);
            }
        }
        return data;
    }

    public static void writeExcel(OutputStream out, boolean is2003File, Iterable<Object[]> sheetRows) {
        writeExcel(out, is2003File, Collections.singletonMap("sheet1", sheetRows), null);
    }

    @SneakyThrows
    public static void writeExcel(OutputStream out, boolean is2003File, Map<String, Iterable<Object[]>> sheets, Function<Row, Row> onRow) {
        try (Workbook workbook = is2003File ? new HSSFWorkbook() : new XSSFWorkbook()) {
            for (Map.Entry<String, Iterable<Object[]>> entry : sheets.entrySet()) {
                Sheet sheet = workbook.getSheet(entry.getKey());
                if (sheet == null) {
                    sheet = workbook.createSheet(entry.getKey());
                }
                int rowIndex = 0;
                for (Object[] cells : entry.getValue()) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        row = sheet.createRow(rowIndex);
                    }
                    for (int i = 0; i < cells.length; i++) {
                        Cell cell = row.getCell(i);
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
                    rowIndex++;
                }
            }
            workbook.write(out);
        }
    }
}
