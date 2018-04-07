/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package de.pixart.messenger.utils;

import android.content.Context;
import android.support.annotation.ColorInt;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.pixart.messenger.R;
import de.pixart.messenger.ui.util.Color;
import rocks.xmpp.addr.Jid;

public class IrregularUnicodeBlockDetector {

    private static final Map<Character.UnicodeBlock,Character.UnicodeBlock> NORMALIZATION_MAP;

    static {
        Map<Character.UnicodeBlock,Character.UnicodeBlock> temp = new HashMap<>();
        temp.put(Character.UnicodeBlock.LATIN_1_SUPPLEMENT, Character.UnicodeBlock.BASIC_LATIN);
        NORMALIZATION_MAP = Collections.unmodifiableMap(temp);
    }

    private static Character.UnicodeBlock normalize(Character.UnicodeBlock in) {
        if (NORMALIZATION_MAP.containsKey(in)) {
            return NORMALIZATION_MAP.get(in);
        } else {
            return in;
        }
    }

    private static final LruCache<Jid, Pattern> CACHE = new LruCache<>(100);

    public static Spannable style(Context context, Jid jid) {
        return style(jid, Color.get(context, R.attr.color_warning));
    }

    private static Spannable style(Jid jid, @ColorInt int color) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        if (jid.getLocal() != null) {
            SpannableString local = new SpannableString(jid.getLocal());
            Matcher matcher = find(jid).matcher(local);
            while (matcher.find()) {
                if (matcher.start() < matcher.end()) {
                    local.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            builder.append(local);
            builder.append('@');
        }
        if (jid.getDomain() != null) {
            builder.append(jid.getDomain());
        }
        if (builder.length() != 0 && jid.getResource() != null) {
            builder.append('/');
            builder.append(jid.getResource());
        }
        return builder;
    }

    private static Map<Character.UnicodeBlock, List<String>> map(Jid jid) {
        Map<Character.UnicodeBlock, List<String>> map = new HashMap<>();
        String local = jid.getLocal();
        final int length = local.length();
        for (int offset = 0; offset < length; ) {
            final int codePoint = local.codePointAt(offset);
            Character.UnicodeBlock block = normalize(Character.UnicodeBlock.of(codePoint));
            List<String> codePoints;
            if (map.containsKey(block)) {
                codePoints = map.get(block);
            } else {
                codePoints = new ArrayList<>();
                map.put(block, codePoints);
            }
            codePoints.add(String.copyValueOf(Character.toChars(codePoint)));
            offset += Character.charCount(codePoint);
        }
        return map;
    }

    private static Set<String> eliminateFirstAndGetCodePoints(Map<Character.UnicodeBlock, List<String>> map) {
        Character.UnicodeBlock block = Character.UnicodeBlock.BASIC_LATIN;
        int size = 0;
        for (Map.Entry<Character.UnicodeBlock, List<String>> entry : map.entrySet()) {
            if (entry.getValue().size() > size) {
                size = entry.getValue().size();
                block = entry.getKey();
            }
        }
        map.remove(block);
        Set<String> all = new HashSet<>();
        for (List<String> codePoints : map.values()) {
            all.addAll(codePoints);
        }
        return all;
    }

    private static Pattern find(Jid jid) {
        synchronized (CACHE) {
            Pattern pattern = CACHE.get(jid);
            if (pattern != null) {
                return pattern;
            }
            pattern = create(eliminateFirstAndGetCodePoints(map(jid)));
            CACHE.put(jid, pattern);
            return pattern;
        }
    }

    private static Pattern create(Set<String> codePoints) {
        final StringBuilder pattern = new StringBuilder();
        for (String codePoint : codePoints) {
            if (pattern.length() != 0) {
                pattern.append('|');
            }
            pattern.append(Pattern.quote(codePoint));
        }
        return Pattern.compile(pattern.toString());
    }
}