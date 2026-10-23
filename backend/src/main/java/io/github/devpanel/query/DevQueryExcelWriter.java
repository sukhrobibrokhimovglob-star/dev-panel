package io.github.devpanel.query;

import io.github.devpanel.support.DevPanelException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Streams a query result into an .xlsx whose first row holds the column names, so even an empty
 * result keeps its header. Only created when Apache POI ({@code poi-ooxml}) is on the classpath.
 */
public class DevQueryExcelWriter implements ResultSetExtractor<byte[]> {

  /** The header takes row 0, so this is also the number of data rows that fit. */
  static final int EXCEL_MAX_ROWS = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
  private static final int EXCEL_MAX_TEXT = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
  /** Excel keeps 15 significant digits, so longer numbers (ids, national ID numbers) are written as text. */
  private static final int EXCEL_MAX_DIGITS = 15;

  /** SXSSF keeps only a window of the rows in memory. */
  @Override
  public byte[] extractData(ResultSet resultSet) throws SQLException {
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSFWorkbook.DEFAULT_WINDOW_SIZE);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Result");
      ExcelStyles styles = new ExcelStyles(workbook);

      Row header = sheet.createRow(0);
      for (int column = 0; column < columnCount; column++) {
        String label = metaData.getColumnLabel(column + 1);
        Cell cell = header.createCell(column);
        cell.setCellValue(label);
        cell.setCellStyle(styles.header());
        // SXSSF cannot autosize columns from rows already flushed to disk, so size by the header
        sheet.setColumnWidth(column, Math.min(Math.max(label.length() + 4, 12), 60) * 256);
      }
      sheet.createFreezePane(0, 1);

      int rowIndex = 1;
      while (resultSet.next()) {
        if (rowIndex > EXCEL_MAX_ROWS) {
          throw DevPanelException.badRequest(
              "The result has more rows than an Excel sheet holds (" + EXCEL_MAX_ROWS + ")");
        }
        Row row = sheet.createRow(rowIndex++);
        for (int column = 0; column < columnCount; column++) {
          writeCell(row.createCell(column), resultSet.getObject(column + 1), styles);
        }
      }
      if (columnCount > 0) {
        sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, columnCount - 1));
      }

      workbook.write(out);
      workbook.dispose();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeCell(Cell cell, Object value, ExcelStyles styles)
      throws SQLException {
    if (value == null) {
      return;
    }
    if (value instanceof Boolean bool) {
      cell.setCellValue(bool);
    } else if (value instanceof Number number && fitsExcelPrecision(number)) {
      cell.setCellValue(number.doubleValue());
    } else if (value instanceof Timestamp timestamp) {
      cell.setCellValue(timestamp.toLocalDateTime());
      cell.setCellStyle(styles.dateTime());
    } else if (value instanceof Date date) {
      cell.setCellValue(date.toLocalDate());
      cell.setCellStyle(styles.date());
    } else if (value instanceof Array array && array.getArray() instanceof Object[] items) {
      cell.setCellValue(truncate(Arrays.deepToString(items)));
    } else {
      cell.setCellValue(truncate(String.valueOf(value)));
    }
  }

  private static boolean fitsExcelPrecision(Number number) {
    if (number instanceof Double || number instanceof Float) {
      return true;
    }
    BigDecimal decimal;
    if (number instanceof BigDecimal big) {
      decimal = big;
    } else if (number instanceof BigInteger big) {
      decimal = new BigDecimal(big);
    } else {
      decimal = BigDecimal.valueOf(number.longValue());
    }
    return decimal.stripTrailingZeros().precision() <= EXCEL_MAX_DIGITS;
  }

  private static String truncate(String text) {
    return text.length() > EXCEL_MAX_TEXT ? text.substring(0, EXCEL_MAX_TEXT) : text;
  }

  private record ExcelStyles(CellStyle header, CellStyle date, CellStyle dateTime) {

    private ExcelStyles(Workbook workbook) {
      this(headerStyle(workbook), formatStyle(workbook, "dd.mm.yyyy"),
          formatStyle(workbook, "dd.mm.yyyy hh:mm:ss"));
    }

    private static CellStyle headerStyle(Workbook workbook) {
      Font bold = workbook.createFont();
      bold.setBold(true);
      CellStyle style = workbook.createCellStyle();
      style.setFont(bold);
      return style;
    }

    private static CellStyle formatStyle(Workbook workbook, String pattern) {
      DataFormat format = workbook.createDataFormat();
      CellStyle style = workbook.createCellStyle();
      style.setDataFormat(format.getFormat(pattern));
      return style;
    }
  }
}
