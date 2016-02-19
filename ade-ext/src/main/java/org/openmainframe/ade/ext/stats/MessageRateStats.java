/*
 
    Copyright IBM Corp. 2015, 2016
    This file is part of Anomaly Detection Engine for Linux Logs (ADE).

    ADE is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    ADE is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with ADE.  If not, see <http://www.gnu.org/licenses/>.
 
*/
package org.openmainframe.ade.ext.stats;

import java.util.Collection;
import java.util.HashMap;
import java.util.TimeZone;

import org.openmainframe.ade.Ade;
import org.openmainframe.ade.exceptions.AdeException;
import org.openmainframe.ade.ext.AdeExt;
import org.openmainframe.ade.ext.service.AdeExtUsageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Days;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * This class keep track of the Interval Message Rate.  Interval in this class has a different meaning from
 * Ade.
 * 
 * Interval is the time that data are kept.  Within this interval, the data are kept for each 10 minutes.  
 * Statistics will be generated by combining a subset of these 10 minutes interval.  
 * 
 * For example, for 120 minutes interval, we might want statistics for every 10, 20, 30, 60 and 120 minutes.
 */
public class MessageRateStats {
    /**
     * A map from Source to Analysis Group
     */
    private static HashMap<String, String> s_sourceToAnalysisGroupMap = new HashMap<String, String>();

    public static void addSourceAndAnalysisGroup(String sourceName, String analysisGroupName) {
        s_sourceToAnalysisGroupMap.put(sourceName, analysisGroupName);
    }

    /**
     * A map containing the message Rate Stats 
     */
    private static HashMap<String, MessageRateStats> s_sourceToMsgRatesStatsMap = new HashMap<String, MessageRateStats>();

    public static MessageRateStats getMessageRateStatsForSource(String source) throws AdeException {
        MessageRateStats stats;
        String name;

        /* Override the source name to "AllSource" if we want to merge all the sources */
        if (AdeExt.getAdeExt().getConfigProperties().isMsgRateMergeSource()) {
            name = s_sourceToAnalysisGroupMap.get(source);
            if (name == null) {
                name = "[ALL_SOURCES]";
            }
        } else {
            name = source;
        }

        stats = s_sourceToMsgRatesStatsMap.get(name);

        if (stats == null) {
            stats = new MessageRateStats(name);
            s_sourceToMsgRatesStatsMap.put(name, stats);
        }

        return stats;
    }

    /**
     * Generate a for all sources
     * @throws AdeException 
     */
    public static void generateReportForAllSources() throws AdeException {
        final Collection<MessageRateStats> statsSourceCollection = s_sourceToMsgRatesStatsMap.values();

        for (MessageRateStats statsForASource : statsSourceCollection) {
            statsForASource.generateReport();
        }
    }

    /**
     * ENUM with the reporting frequency
     */
    public enum ReportFrequency {
        MONTHLY, DAYS(10);

        private int m_days;

        /**
         * Number of days
         * @param days
         */
        private ReportFrequency(int days) {
            m_days = days;
        }

        /**
         * This is used for non-days based frequency
         */
        private ReportFrequency() {

        }

        /**
         * Return the days
         * @return
         */
        public void setDays(int days) {
            m_days = days;
        }

        /**
         * Return the days
         * @return
         */
        public int getDays() {
            return m_days;
        }

        @Override
        public String toString() {
            String str = "";
            switch (this) {
                case DAYS:
                    str = this.name() + "(" + getDays() + ")";
                    break;
                case MONTHLY:
                    str = this.name();
                    break;
                default:
                    str = "ERROR";
            }
            return str;
        }
    }

    /**
     * 10 minutes
     */
    private static final long TEN_MINUTES = 10 * 60 * 1000;

    /**
     * Output format
     */
    private static final DateTimeFormatter s_dateTimeFormatter = DateTimeFormat.forPattern("MM/dd/yyyy HH:mm:ss");

    /**
     * The outputTimeZone defined in the conf file.
     */
    private static DateTimeZone s_outTimeZone;

    /**
     * The frequency where reports are generated
     */
    private ReportFrequency m_reportFrequency;

    /**
     * The different time used for reporting.
     */
    private DateTime m_lastReportDateTimeBegin;
    private DateTime m_processingStartDateTime;

    /**
     * The input time of the most recent message
     */
    private DateTime m_messageInputDateTime;

    /**
     * Logger
     */
    private static final Logger logger = LoggerFactory.getLogger(MessageRateStats.class);

    /**
     * Logger for statistics
     */
    private static final Logger statsLogger = LoggerFactory.getLogger(MessageRateStats.class);

    /**
     * The length of the data to keep.  This will be used to determine the size of array for
     * tracking the message count.  
     */
    private short m_numberOf10MinutesSlotsToKeep;

    /**
     * The interval size statistics to keep
     */
    private short[] m_subIntervalSizeList;
    private static final short[] SUB_INTERVAL_SIZE_LIST_TWO_HOURS = {1, 2, 3, 6, 12 };

    /**
     * Each index of this array is map to the subIntervalSizeList.
     * 
     * Each index represent the overall statistics for a subinterval size (10, 20 minutes etc) 
     */
    private OverallStats[] m_overallStatsForAllIntervals;

    /**
     * Beginning of interval, initialize to 0 to indicate it's not initialized yet.
     */
    private long m_beginOfInterval = 0;
    private long m_beginOfNextInterval = 0;

    /**
     * The current index of the 10MinutesMsgCountArray 
     */
    private int m_currentIndex10MinutesMsgCountArray = 0;

    /**
     * A map from Msg ID to the MsgStats object.
     */
    private HashMap<String, MessageStats> m_msgIdToMsgStatsMap = new HashMap<String, MessageRateStats.MessageStats>();
    private final static int MAX_MESSAGE_STATS_TO_KEEP = 1000;
    private static int s_maxMsgToKeep = 1000;

    /**
     * The source this stats object represents
     */
    private String m_source;

    /**
     * A default constructor
     * @param source 
     * @throws AdeException 
     */
    private MessageRateStats(String source) throws AdeException {
        /* Default the interval size to keep 12 ten minutes data. */
        this(source, (short) 12, SUB_INTERVAL_SIZE_LIST_TWO_HOURS);
    }

    /**
     * Constructor
     * @param source 
     * @throws AdeException 
     */
    private MessageRateStats(String source, short numberOf10MinutesIntervalToKeep, short[] intervalSizeList)
            throws AdeException {
        m_source = source;

        getConfiguration(numberOf10MinutesIntervalToKeep, intervalSizeList);

        if (intervalSizeList.length == 0) {
            final String msg = "tenMinutesIntervalSizeList has a size of 0";
            logger.warn(msg);
            throw new AdeExtUsageException(msg);
        }

        /* Verify if the number of interval to keep is divisible by intervalSizeList */
        for (short intervalSize : m_subIntervalSizeList) {
            final int reminder = m_numberOf10MinutesSlotsToKeep % intervalSize;
            if (reminder != 0) {
                final String msg = "m_numberOf10MinutesIntervalToKeep(" + m_numberOf10MinutesSlotsToKeep + ") is not divisible by tenMinutesIntervalSizeList value: " + intervalSize;
                logger.warn(msg);
                throw new AdeExtUsageException(msg);
            }
        }

        /* Initialize the OverStats object for each requested interval size. */
        initOverallStatsForAllIntervals();
    }

    /**
     * Retrieve the configuration parameters
     * @throws AdeException 
     */
    private void getConfiguration(short numberOf10MinutesSlotsToKeep, short[] intervalSizeList) throws AdeException {
        final TimeZone outJavaTimeZone = Ade.getAde().getConfigProperties().getOutputTimeZone();
        s_outTimeZone = DateTimeZone.forOffsetMillis(outJavaTimeZone.getRawOffset());

        /* Set the number of messages to keep */
        s_maxMsgToKeep = AdeExt.getAdeExt().getConfigProperties().getMsgRateMsgToKeep();
        if (s_maxMsgToKeep == -1) {
            s_maxMsgToKeep = MAX_MESSAGE_STATS_TO_KEEP;
        }

        /* Set the report frequency */
        final String reportFreqStr = AdeExt.getAdeExt().getConfigProperties().getMsgRateReportFreq();
        if (reportFreqStr == null || reportFreqStr.length() == 0) {
            m_reportFrequency = ReportFrequency.DAYS;
            m_reportFrequency.setDays(10);
        } else if (reportFreqStr.equalsIgnoreCase(ReportFrequency.MONTHLY.name())) {
            m_reportFrequency = ReportFrequency.MONTHLY;
        } else {
            final int reportFreq = Integer.parseInt(reportFreqStr);
            m_reportFrequency = ReportFrequency.DAYS;
            m_reportFrequency.setDays(reportFreq);
        }

        /* Set the number of 10 minutes interval to keep */
        m_numberOf10MinutesSlotsToKeep = AdeExt.getAdeExt().getConfigProperties().getMsgRate10MinSlotsToKeep();
        if (m_numberOf10MinutesSlotsToKeep == -1) {
            m_numberOf10MinutesSlotsToKeep = numberOf10MinutesSlotsToKeep;
        }

        /* Set the subinterval list */
        m_subIntervalSizeList = AdeExt.getAdeExt().getConfigProperties().getMsgRate10MinSubIntervals();
        if (m_subIntervalSizeList == null) {
            m_subIntervalSizeList = intervalSizeList;
        }

        /* Output the trace settings */
        StringBuilder bldtrace = new StringBuilder("");
        for (short interval : m_subIntervalSizeList) {
            bldtrace.append(interval + ",");
        }
        logger.info("Tracking Message Rate for " + m_source + " for "
                + m_numberOf10MinutesSlotsToKeep + " ten minutes slots"
                + " reportFreq=" + m_reportFrequency.toString()
                + " maxMsgToKeep=" + s_maxMsgToKeep
                + " 10MinIntervals=" + bldtrace.toString());
    }

    /**
     * Add a message to the collection
     * @throws AdeException 
     */
    public void markLoggerStarting(long nextMessageInputTime) throws AdeException {
        if (m_messageInputDateTime == null) {
            /* This is the first time we process a process for this source, and this message indicate the logger
             * just started.  No logging needed. */
            return;
        }

        /* Find the starting of the next ten minutes interval for the previous message. */
        long prevMessageInputTime = m_messageInputDateTime.getMillis();
        if (prevMessageInputTime % TEN_MINUTES > 0) {

            prevMessageInputTime = TEN_MINUTES * (long) (prevMessageInputTime / TEN_MINUTES) + TEN_MINUTES;
        }
        final DateTime prevMessageDateTime = new DateTime(prevMessageInputTime).withZone(s_outTimeZone);

        /* Find the end of the 10 minutes interval before the next message */
        nextMessageInputTime = TEN_MINUTES * (long) (nextMessageInputTime / TEN_MINUTES);
        final DateTime nextMessageDateTime = new DateTime(nextMessageInputTime).withZone(s_outTimeZone);

        /* Find the number of 10 minutes interval being skipped */
        final long skippedInterval = (nextMessageInputTime - prevMessageInputTime) / TEN_MINUTES;

        /* Note: Skipped Interval could be less than 0, if the next Message and pre Message are within
         * the same 10 minutes interval.   */
        if (skippedInterval > 0) {
            /* Write out the message, using the previous message's timestamp */
            final DateTime dateTime = m_messageInputDateTime;
            final String dateStr = s_dateTimeFormatter.print(dateTime);
            final String reportString = m_source + ", " + dateStr
                    + ", Logger Unavailable For (10 min intervals)=" + skippedInterval
                    + ", emptyIntervalStart=" + s_dateTimeFormatter.print(prevMessageDateTime)
                    + ", emptyIntervalEnd=" + s_dateTimeFormatter.print(nextMessageDateTime);
            statsLogger.info(reportString);
        }
    }

    /**
     * Add a message to the collection
     * @throws AdeException 
     */
    public void addMessage(String msgId, long inputTime, boolean isWrapperMessage) throws AdeException {
        m_messageInputDateTime = new DateTime(inputTime).withZone(s_outTimeZone);

        /* Update the time/index as needed */
        determineAndProcessEndOfInterval(inputTime);

        /* Look up the msgRateStats for the msgId.  If not exist, add it */
        MessageStats msgRateStats = m_msgIdToMsgStatsMap.get(msgId);
        if (msgRateStats == null) {
            /* The number of entry allowed to add to the HashMap is limited,
             * this is to control memory consumption.  
             * 
             * The goal for this Message Rate tracker is to determine 
             * if there are "enough" unique messages, if there are more than 
             * 1000 in 2 hours, it already satisfied our needs.Was
             */
            if (m_msgIdToMsgStatsMap.size() >= s_maxMsgToKeep) {
                return;
            }

            msgRateStats = new MessageStats(msgId, m_numberOf10MinutesSlotsToKeep, isWrapperMessage);
            m_msgIdToMsgStatsMap.put(msgId, msgRateStats);
        }

        /* The inputTime is guarantee to be in the current interval or before. 
         * If inputTime is less than beginning of current interval (indexOf10MinutesMsgCountArray will be negative), 
         * or it's less than current 10 minutes index (i.e. message's time moved backward),  
         * Then, use m_currentIndex10MinutesMsgCountArray set previously.*/
        final int indexOf10MinutesMsgCountArray = (int) ((inputTime - m_beginOfInterval) / TEN_MINUTES);
        if (indexOf10MinutesMsgCountArray > m_currentIndex10MinutesMsgCountArray) {
            m_currentIndex10MinutesMsgCountArray = indexOf10MinutesMsgCountArray;
        }

        /* Indicate that the message has occured */
        if (inputTime >= m_beginOfInterval) {
            /* Only if the message is > begin of interval, then we will add it.
             * This will allow message go back in time at most 2 hours.
             */
            msgRateStats.addMessage(m_currentIndex10MinutesMsgCountArray);
        }

    }

    /**
     * Manage the time, and set the proper index.
     * @throws AdeException 
     */
    private void determineAndProcessEndOfInterval(long inputTime) throws AdeException {
        if (m_beginOfInterval == 0) {
            /* Determine the beginning of interval */
            m_beginOfInterval = (inputTime / m_numberOf10MinutesSlotsToKeep / TEN_MINUTES) * m_numberOf10MinutesSlotsToKeep * TEN_MINUTES;
            m_beginOfNextInterval = m_beginOfInterval + m_numberOf10MinutesSlotsToKeep * TEN_MINUTES;

            /* Keep the last report time and when this process started */
            m_lastReportDateTimeBegin = new DateTime(m_beginOfInterval).withZone(s_outTimeZone);
            m_lastReportDateTimeBegin = m_lastReportDateTimeBegin.withTimeAtStartOfDay();

            m_processingStartDateTime = new DateTime(m_lastReportDateTimeBegin.getMillis()).withZone(s_outTimeZone);
        } else {
            boolean isNewInterval = false;

            /* Message's input time is greater than current interval */
            if (inputTime >= m_beginOfNextInterval) {
                endOfIntervalProcessing(inputTime);
                isNewInterval = true;
                m_beginOfInterval = m_beginOfNextInterval;
                m_beginOfNextInterval = m_beginOfInterval + m_numberOf10MinutesSlotsToKeep * TEN_MINUTES;

                /* Reset the count array index to 0 */
                m_currentIndex10MinutesMsgCountArray = 0;
            }

            /* if the inputTime is still greater than the next interval, 
             * continue to find the beginning of next interval. */
            while (inputTime >= m_beginOfNextInterval) {
                endOfIntervalProcessing(inputTime);

                /* We don't call endOfIntervalProcessing() here, because those interval missed 
                 * are considered no data and shouldn't affect the stddev calculation. */
                m_beginOfInterval = m_beginOfNextInterval;
                m_beginOfNextInterval = m_beginOfInterval + m_numberOf10MinutesSlotsToKeep * TEN_MINUTES;
            }

            if (isNewInterval) {
                /* If it's time to generate report, then generate the report */
                generateReportIfNeeded(inputTime);
            }
        }
    }

    /**
     * Handle end of interval processing.  
     * 
     * During end of a 120 minutes interval, we will consolidate all the stats collected 
     * for each message (i.e. for a specific 10, 20, 30 minutes interval, how many unique 
     * message is there).  The consolidated stats for 10 minutes interval will be added 
     * to the "overallStats" object for 10 minutes interval (same for 20, 30 minutes).
     * 
     * These overallStats will get reset once a report is generated and outputted.
     * @param inputTime 
     * @throws AdeException 
     */
    private void endOfIntervalProcessing(long inputTime) throws AdeException {
        /* Initialize an array to keep track of the subInterval statistics */
        final long[][] arrayOfSubIntervalMsg1UniqueMsgIdCount = new long[m_subIntervalSizeList.length][];
        final long[][] arrayOfSubIntervalMsg1TotalMsgCount = new long[m_subIntervalSizeList.length][];

        final long[][] arrayOfSubIntervalMsg2UniqueMsgIdCount = new long[m_subIntervalSizeList.length][];
        final long[][] arrayOfSubIntervalMsg2TotalMsgCount = new long[m_subIntervalSizeList.length][];

        for (int i = 0; i < m_subIntervalSizeList.length; i++) {
            final short intervalCount = m_subIntervalSizeList[i];
            arrayOfSubIntervalMsg1UniqueMsgIdCount[i] = new long[m_numberOf10MinutesSlotsToKeep / intervalCount];
            arrayOfSubIntervalMsg1TotalMsgCount[i] = new long[m_numberOf10MinutesSlotsToKeep / intervalCount];

            arrayOfSubIntervalMsg2UniqueMsgIdCount[i] = new long[m_numberOf10MinutesSlotsToKeep / intervalCount];
            arrayOfSubIntervalMsg2TotalMsgCount[i] = new long[m_numberOf10MinutesSlotsToKeep / intervalCount];
        }

        /* Go through all messages */
        final Collection<MessageStats> msgStatsCollection = m_msgIdToMsgStatsMap.values();
        for (MessageStats msgStats : msgStatsCollection) {
            for (int i = 0; i < m_subIntervalSizeList.length; i++) {
                final short subIntervalSize = m_subIntervalSizeList[i];

                final long[] intervalCountArray = msgStats.getCountBasedOnIntervalSize(subIntervalSize);

                /* Determine number of the subInterval has message */
                for (int j = 0; j < intervalCountArray.length; j++) {
                    final long intervalCount = intervalCountArray[j];

                    if (msgStats.isMsg1()) {
                        /* Total Message Count */
                        arrayOfSubIntervalMsg1TotalMsgCount[i][j] += intervalCount;

                        /* Unique Message Count */
                        if (intervalCount > 0) {
                            /* Increase the count array, once per message per interval if 
                             * the message appears at least once.
                             */
                            arrayOfSubIntervalMsg1UniqueMsgIdCount[i][j]++;
                        }
                    } else {
                        /* Total Message Count */
                        arrayOfSubIntervalMsg2TotalMsgCount[i][j] += intervalCount;

                        /* Unique Message Count */
                        if (intervalCount > 0) {
                            /* Increase the count array, once per message per interval if 
                             * the message appears at least once.
                             */
                            arrayOfSubIntervalMsg2UniqueMsgIdCount[i][j]++;
                        }
                    }
                }
            }
        }

        /* Clear the list of msg that we are tracking. */
        m_msgIdToMsgStatsMap.clear();

        /* Add the statistics from the interval to the overallStats*/
        for (int i = 0; i < m_subIntervalSizeList.length; i++) {
            m_overallStatsForAllIntervals[i].addStats(
                    arrayOfSubIntervalMsg1UniqueMsgIdCount[i], arrayOfSubIntervalMsg1TotalMsgCount[i],
                    arrayOfSubIntervalMsg2UniqueMsgIdCount[i], arrayOfSubIntervalMsg2TotalMsgCount[i]);
        }
    }

    /**
     * Initialize the overallStats for all interval size.  If overallStats array already 
     * exist, it will be overwritten.
     */
    private void initOverallStatsForAllIntervals() {
        m_overallStatsForAllIntervals = new OverallStats[m_subIntervalSizeList.length];

        for (int i = 0; i < m_subIntervalSizeList.length; i++) {
            final short subIntervalSize = m_subIntervalSizeList[i];

            m_overallStatsForAllIntervals[i] = new OverallStats(subIntervalSize);
        }
    }

    /**
     * Output the overallStats to logger
     * @throws AdeException 
     */
    private void generateReportIfNeeded(long inputTime) throws AdeException {
        final DateTime inputDateTimeStartOfDay = new DateTime(inputTime).withZone(s_outTimeZone).withTimeAtStartOfDay();
        final int daysSinceLastReported = Days.daysBetween(m_lastReportDateTimeBegin, inputDateTimeStartOfDay).getDays();
        if (daysSinceLastReported < 1) {
            /* Do not need report if the days is less than 1 day */
            return;
        }

        boolean createReport = false;
        boolean resetData = false;

        switch (m_reportFrequency) {
            case MONTHLY: {
                final boolean monthChangedSinceLastReported = m_lastReportDateTimeBegin.getMonthOfYear() != inputDateTimeStartOfDay.getMonthOfYear()
                        || m_lastReportDateTimeBegin.getYear() != inputDateTimeStartOfDay.getYear();
    
                if (monthChangedSinceLastReported) {
                    createReport = true;
                    resetData = true;
                } else {
                    resetData = false;
    
                    /* If this processing is within the same month as processing start, 
                     * output every day */
                    final boolean monthChangedSinceProcessingStarted = m_processingStartDateTime.getMonthOfYear() != inputDateTimeStartOfDay.getMonthOfYear()
                            || m_processingStartDateTime.getYear() != inputDateTimeStartOfDay.getYear();
                    if (!monthChangedSinceProcessingStarted) {
                        createReport = true;
                    }
                }
                break;
            }
            case DAYS: {
                if (daysSinceLastReported >= m_reportFrequency.getDays()) {
                    createReport = true;
                    resetData = true;
                } else {
                    resetData = false;
    
                    /* If this processing just started less than or equal to 10 days, output every day */
                    final int daysSinceStarted = Days.daysBetween(m_processingStartDateTime, inputDateTimeStartOfDay).getDays();
                    if (daysSinceStarted <= m_reportFrequency.getDays()) {
                        createReport = true;
                    }
                }
                break;
            }
            default:
                break;
        }

        /* Write out the report */
        if (createReport) {
            generateReport(getYesterdayEndOfDay(inputDateTimeStartOfDay));
            m_lastReportDateTimeBegin = inputDateTimeStartOfDay;

            /* Reset all the data */
            if (resetData) {
                initOverallStatsForAllIntervals();
            }
        }
    }

    /**
     * Return the end of day of yesterday
     * @param dateTime
     * @return
     */
    private DateTime getYesterdayEndOfDay(DateTime dateTime) {
        dateTime = dateTime.withTimeAtStartOfDay();
        dateTime = dateTime.minusMillis(1);

        return dateTime;
    }

    /**
     * Generate a report
     * @throws AdeException 
     */
    public void generateReport() throws AdeException {
        /* Note: generateReport without any input time is called when EndOfStream is reached for a reader.  
         * There could be more stream coming in, or there could be no more.
         * 
         * When this is called, the timestamp of the message last seen will be used.
         */
        if (m_messageInputDateTime != null) {
            generateReport(m_messageInputDateTime);
        } else {
            generateReport("EndOfFile_No_Date");
        }
    }

    /**
     * 
     * @param dateStr
     * @throws AdeException
     */
    public void generateReport(DateTime dateTime) throws AdeException {
        final String dateStr = s_dateTimeFormatter.print(dateTime);
        generateReport(dateStr);
    }

    /**
     * Generate a report
     * @throws AdeException 
     */
    private void generateReport(String dateStr) throws AdeException {
        /* Write the log for parsing errors */
        MessagesWithParseErrorStats.getParserErrorStats().writeToLog();

        final String reportString = m_source + ", " + dateStr + ", ";
        for (OverallStats overallStats : m_overallStatsForAllIntervals) {
            final String trace = reportString + " " + overallStats.toString();
            statsLogger.info(trace);
        }
    }

    @Override
    public String toString() {
        final DateTime beginOfIntervalDateTime = new DateTime(m_beginOfInterval).withZone(s_outTimeZone);

        String trace = "source=" + m_source + " curIndex=" + m_currentIndex10MinutesMsgCountArray;
        trace += " inputDateTime=" + m_messageInputDateTime;
        trace += " lastReportDateTimeStartOfDay=" + m_lastReportDateTimeBegin;
        trace += "\n beginProcessingDateTime=" + m_processingStartDateTime;
        trace += " beginInterval=" + beginOfIntervalDateTime;
        return trace;
    }

    private static class MessageStats {
        private String m_msgId;
        private int[] m_10MinutesMsgCountArray;
        private boolean m_isMsg2;

        /**
         * Create a new object for a message
         */
        public MessageStats(String msgId, int numberOf10MinutesInterval, boolean isMsg2) {
            m_msgId = msgId;
            m_10MinutesMsgCountArray = new int[numberOf10MinutesInterval];
            m_isMsg2 = isMsg2;
        }

        /**
         * Indicate a new occurrance of this message
         */
        public void addMessage(int index) {
            /* Limit the count to MAX, so that it will not wrap over */
            if (m_10MinutesMsgCountArray[index] < Integer.MAX_VALUE) {
                m_10MinutesMsgCountArray[index]++;
            }
        }

        /**
         * Given a requested interval size (10, 20 minutes), generate a count of occurrence for 
         * that interval.
         * 
         * @return
         */
        public long[] getCountBasedOnIntervalSize(short numberOf10MinutesSlotPerSubInterval) {
            final int numberOfSubIntervals = m_10MinutesMsgCountArray.length / numberOf10MinutesSlotPerSubInterval;
            final long[] subIntervals = new long[numberOfSubIntervals];

            /* Sum up every N entry in the array */
            for (int i = 0; i < subIntervals.length; i++) {
                long value = 0;
                for (int j = 0; j < numberOf10MinutesSlotPerSubInterval; j++) {
                    final int tenMinutesArrayIndex = i * numberOf10MinutesSlotPerSubInterval + j;
                    value += m_10MinutesMsgCountArray[tenMinutesArrayIndex];
                }

                subIntervals[i] = value;
            }

            return subIntervals;
        }

        /**
         * Whether this message is 2nd message
         * @return
         */
        public boolean isMsg1() {
            return !m_isMsg2;
        }

        @Override
        public String toString() {
            StringBuilder bldstr = new StringBuilder(m_msgId);
            for (int i = 0; i < m_10MinutesMsgCountArray.length; i++) {
                final int count = m_10MinutesMsgCountArray[i];
                bldstr.append(", i" + i + "=" + count);
            }

            return bldstr.toString();
        }
    }

    static class OverallStats {
        /**
         * The size of Interval this OverallStats object present
         */
        private short m_intervalSizeRepresented;

        /**
         * The total number of intervals
         */
        private long m_numberOfIntervals;

        /**
         * The total number of intervals
         */
        private long m_intervalWithZeroCounts;

        /**
         * The sum of count all the intervals
         */
        private long m_msg1TotalCount;
        private long m_msg2TotalCount;

        /**
         * The sum of count all the intervals
         */
        private long m_sumOfMsg1UniqueMsgIdCount;
        private long m_sumOfMsg2UniqueMsgIdCount;

        /**
         * The sum of square of the interval data
         */
        private long m_sumOfMsg1UniqueMsgIdCountSquare;

        /**
         * The smallest count
         */
        private long m_minMsg1UniqueMsgIdCount = Long.MAX_VALUE;

        /**
         * The largest count
         */
        private long m_maxMsg1UniqueMsgIdCount = 0;

        /**
         * Constructor
         * @param intervalSize
         */
        public OverallStats(short intervalSizeRepresented) {
            m_intervalSizeRepresented = intervalSizeRepresented;
        }

        /**
         * Add an array of stats
         * @param intervalSize
         */
        public void addStats(long[] msg1UniqueMsgIdStats, long[] msg1TotalStats,
                long[] msg2UniqueMsgIdStats, long[] msg2TotalStats) {
            m_numberOfIntervals += msg1UniqueMsgIdStats.length;

            for (int i = 0; i < msg1UniqueMsgIdStats.length; i++) {
                final long msg1UniqueMsgIdStat = msg1UniqueMsgIdStats[i];
                final long msg1TotalStat = msg1TotalStats[i];

                final long msg2UniqueMsgIdStat = msg2UniqueMsgIdStats[i];
                final long msg2TotalStat = msg2TotalStats[i];

                if (msg1UniqueMsgIdStat == 0) {
                    /* Only need to check Msg1, since Msg2 won't exist unless there is a Msg1 */
                    m_intervalWithZeroCounts++;
                } else {
                    /* Do not make zero as the min, just in case we have partial interval contains no data 
                     * - startup, shutdown etc */
                    m_minMsg1UniqueMsgIdCount = Math.min(m_minMsg1UniqueMsgIdCount, msg1UniqueMsgIdStat);
                }

                m_maxMsg1UniqueMsgIdCount = Math.max(m_maxMsg1UniqueMsgIdCount, msg1UniqueMsgIdStat);

                m_sumOfMsg1UniqueMsgIdCount += msg1UniqueMsgIdStat;
                m_sumOfMsg2UniqueMsgIdCount += msg2UniqueMsgIdStat;

                m_sumOfMsg1UniqueMsgIdCountSquare += msg1UniqueMsgIdStat * msg1UniqueMsgIdStat;

                m_msg1TotalCount += msg1TotalStat;
                m_msg2TotalCount += msg2TotalStat;
            }
        }

        /**
         * Return the total count
         * @return
         */
        public long getMsg1UniqueMsgIdCount() {
            return m_sumOfMsg1UniqueMsgIdCount;
        }

        /**
         * Return the total count
         * @return
         */
        public long getMsg2UniqueMsgIdCount() {
            return m_sumOfMsg2UniqueMsgIdCount;
        }

        /**
         * Return the total count
         * @return
         */
        public long getMsg1TotalCount() {
            return m_msg1TotalCount;
        }

        /**
         * Return the total count
         * @return
         */
        public long getMsg2TotalCount() {
            return m_msg2TotalCount;
        }

        /**
         * Return the interval size
         * @return
         */
        public int getIntervalSize() {
            return m_intervalSizeRepresented;
        }

        /**
         * Return the min
         * @return
         */
        public long getMsg1UniqueMsgIdMin() {
            return m_minMsg1UniqueMsgIdCount;
        }

        /**
         * Return the max
         * @return
         */
        public long getMsg1UniqueMsgIdMax() {
            return m_maxMsg1UniqueMsgIdCount;
        }

        /**
         * Return the mean value
         */
        public double getMsg1UniqueMsgIdMean() {
            final double mean = (double) m_sumOfMsg1UniqueMsgIdCount / (double) m_numberOfIntervals;
            return mean;
        }

        /**
         * Return the mean value
         */
        public double getMsg2UniqueMsgIdMean() {
            final double mean = (double) m_sumOfMsg2UniqueMsgIdCount / (double) m_numberOfIntervals;
            return mean;
        }

        /**
         * Return the total number of intervals
         * @return
         */
        public long getNumberOfIntervals() {
            return m_numberOfIntervals;
        }

        /**
         * Return the total number of intervals
         * @return
         */
        public long getIntervalsWithZeroCount() {
            return m_intervalWithZeroCounts;
        }

        /**
         * Return the mean value
         */
        public double getMsg1UniqueMsgIdVariance() {
            final double variance = (double) m_sumOfMsg1UniqueMsgIdCountSquare / (double) m_numberOfIntervals -
                    -((double) getMsg1UniqueMsgIdMean() * (double) getMsg1UniqueMsgIdMean());
            return variance;
        }

        /**
         * Return the mean value
         */
        public double getMsg1UniqueMsgIdStandardVariation() {
            final double variance = getMsg1UniqueMsgIdVariance();
            final double stdDev = Math.sqrt(variance);
            return stdDev;
        }

        @Override
        public String toString() {
            String trace = "IntervalSize=" + getIntervalSize();
            trace += String.format(", msg1UMIDAvgCount=%.2f", getMsg1UniqueMsgIdMean());
            trace += String.format(", msg2UMIDAvgCount=%.2f", getMsg2UniqueMsgIdMean());
            trace += ", msg1UMIDCount=" + getMsg1UniqueMsgIdCount();
            trace += ", msg2UMIDCount=" + getMsg2UniqueMsgIdCount();
            trace += ", numOfIntervals=" + getNumberOfIntervals();
            trace += String.format(", msg1UMIDStdDev=%.2f", getMsg1UniqueMsgIdStandardVariation());
            trace += ", msg1UMIDMin=" + getMsg1UniqueMsgIdMin();
            trace += ", msg1UMIDMax=" + getMsg1UniqueMsgIdMax();
            trace += ", zeroCountIntervals=" + getIntervalsWithZeroCount();
            trace += ", msg1TotalCount=" + getMsg1TotalCount();
            trace += ", msg2TotalCount=" + getMsg2TotalCount();
            return trace;
        }

    }
}
