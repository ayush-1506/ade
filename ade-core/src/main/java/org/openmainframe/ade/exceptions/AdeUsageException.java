/*
 
    Copyright IBM Corp. 2010, 2016
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
package org.openmainframe.ade.exceptions;

/** 
 * An exception signaling a user or environment problem.
 * E.g., bad configuration parameter, program arguments, etc... 
 */
public class AdeUsageException extends AdeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new {@link AdeUsageException} with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to {@link #initCause}.
     * @see {@link #AdeException(String)}
     * @param message {@inheritDoc}
     */
    public AdeUsageException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@link AdeUsageException} with the specified detail message
     * and cause.
     * @see {@link #AdeException(String, Throwable)}
     * @param message {@inheritDoc}
     * @param rootCause {@inheritDoc}
     */
    public AdeUsageException(String message, Throwable rootCause) {
        super(message, rootCause);
    }

}