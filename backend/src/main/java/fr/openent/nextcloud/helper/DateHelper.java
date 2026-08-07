package fr.openent.nextcloud.helper;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(DateHelper.class);

    public static final String NEXTCLOUD_FORMAT = "EEE, d MMM yyyy HH:mm:ss z";
    public static final String UTC_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    public static SimpleDateFormat getNextcloudSimpleDateFormat() {
        return new SimpleDateFormat(NEXTCLOUD_FORMAT, Locale.ENGLISH);
    }

    public static SimpleDateFormat getUTCSimpleDateFormat() {
        return new SimpleDateFormat(UTC_FORMAT, Locale.ENGLISH);
    }

    public static Date parseDate(String dateString, String format) {
        Date date = new Date();

        // Les dates WebDAV/HTTP (ex. "Wed, 05 Aug 2026 14:37:11 GMT") sont toujours en anglais
        // (RFC 1123) quelle que soit la locale du serveur : Locale.ENGLISH est obligatoire ici,
        // sinon SimpleDateFormat échoue à reconnaître "Wed"/"Aug" sur une JVM en locale fr.
        SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.ENGLISH);
        try {
            date = sdf.parse(dateString);
        } catch (ParseException e) {
            LOGGER.error("[Nextcloud@DateHelper::parseDate] Error when casting date: ", e);
        }

        return date;
    }

    public static String getDateString(Date date, String format) {
        SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.ENGLISH);
        return sdf.format(date);
    }
}
