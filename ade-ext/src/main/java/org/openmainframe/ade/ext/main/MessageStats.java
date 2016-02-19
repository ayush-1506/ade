/*
 
    Copyright IBM Corp. 2012, 2016
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
package org.openmainframe.ade.ext.main;

import org.apache.commons.cli.Options;
import org.openmainframe.ade.Ade;
import org.openmainframe.ade.exceptions.AdeException;
import org.openmainframe.ade.exceptions.AdeInternalException;
import org.openmainframe.ade.exceptions.AdeUsageException;
import org.openmainframe.ade.ext.main.helper.AdeExtRequestType;
import org.openmainframe.ade.ext.main.helper.UploadOrAnalyze;
import org.openmainframe.ade.flow.IMessageInstanceTarget;

/**
 * Load the logMessages and summarize them. Then, store the summary
 * in the database.
 */
public class MessageStats extends UploadOrAnalyze {
    /**
     * The entry point to Upload
     * 
     * @param args
     * @throws AdeException
     */
    public static void main(String[] args) throws AdeException {
        final MessageStats upload = new MessageStats();
        try {
            upload.run(args);
        } catch (AdeUsageException e) {
            upload.getMessageHandler().handleUserException(e);
        } catch (AdeInternalException e) {
            upload.getMessageHandler().handleAdeInternalException(e);
        } catch (AdeException e) {
            upload.getMessageHandler().handleAdeException(e);
        } catch (Throwable e) {
            upload.getMessageHandler().handleUnexpectedException(e);
        } finally {
            upload.quietCleanup();
        }

    }

    /**
     * Constructor
     */
    MessageStats() {
        super(AdeExtRequestType.GENERATE_MESSAGE_STATISTICS);
    }

    /**
     * Return the MessageInstanceTarget object that will process the logMessages.
     * 
     */
    @Override
    protected final IMessageInstanceTarget getMITarget() throws AdeException {
        return Ade.getAde().getActionsFactory().newMessageInstanceStatistics();
    }

    /**
     * Parse the input arguments
     */
    @Override
    protected final void parseArgs(String[] args) throws AdeException {
        super.parseArgs(new Options(), args);
    }

}
