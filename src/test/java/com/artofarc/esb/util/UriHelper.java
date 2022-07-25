/*
 * Copyright 2021 Andre Karalus
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
package com.artofarc.esb.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

public final class UriHelper {
   
   public final static URI createURI(String uri) {
      try {
         return new URI(uri);
      } catch (URISyntaxException e) {
         throw new IllegalArgumentException(e);
      }
   }

	public final static List<String> uriToParts(String ns) {
		URI uri = createURI(ns);
		ArrayList<String> result = new ArrayList<String>();

		if (uri.getAuthority() != null) {
			StringTokenizer tokenizer = new StringTokenizer(uri.getAuthority(), ".");
			while (tokenizer.hasMoreTokens()) {
				result.add(0, tokenizer.nextToken());
			}
		} else if (uri.getScheme() != null && uri.getSchemeSpecificPart() != null) {
			StringTokenizer tokenizer = new StringTokenizer(uri.getSchemeSpecificPart(), ":");
			while (tokenizer.hasMoreTokens()) {
				result.add(tokenizer.nextToken());
			}
		}
		if (uri.getPath() != null) {
			StringTokenizer tokenizer = new StringTokenizer(uri.getPath(), "/");
			while (tokenizer.hasMoreTokens()) {
				result.add(tokenizer.nextToken());
			}
		}
		return result;
	}

	public final static String convertUri(String ns, String separator, Boolean firstUpper) {
		StringBuilder builder = new StringBuilder();
		List<String> uriToParts = uriToParts(ns);
		if (uriToParts.size() > 0) {
			for (Iterator<String> iter = uriToParts.iterator();;) {
				String part = iter.next();
				if (Boolean.TRUE.equals(firstUpper)) {
					builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
				} else {
					builder.append(part);
				}
				if (!iter.hasNext()) break;
				builder.append(separator);
			}
		}
		return builder.toString();
	}

}
