/*
 * Copyright 2026 Andre Karalus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.artofarc.util;

import java.util.Calendar;
import java.util.TimeZone;

import javax.xml.bind.DatatypeConverter;

public class DatatypeHelper {

	private DatatypeHelper() {
	}

	private final static TimeZone TIME_ZONE;

	static {
		String timezone = System.getProperty("esb0.mapper.timezone");
		TIME_ZONE = timezone != null ? TimeZone.getTimeZone(timezone) : TimeZone.getDefault();
	}

	public static Calendar getCalendarInstance() {
		return new java.util.GregorianCalendar(TIME_ZONE);// Faster than Calendar.getInstance(TIME_ZONE);
	}

	public static String printDateTime(Calendar val) {
		return DatatypeConverter.printDateTime(val);
	}

	public static String printDate(Calendar val) {
		return DatatypeConverter.printDate(val);
	}

	public static String printBase64Binary(byte[] val) {
		return DatatypeConverter.printBase64Binary(val);
	}

}
