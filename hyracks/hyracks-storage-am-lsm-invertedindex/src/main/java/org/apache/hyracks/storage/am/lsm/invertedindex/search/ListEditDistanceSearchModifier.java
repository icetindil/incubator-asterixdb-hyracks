/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hyracks.storage.am.lsm.invertedindex.search;

import org.apache.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndexSearchModifier;

public class ListEditDistanceSearchModifier implements IInvertedIndexSearchModifier {

    protected int edThresh;

    public ListEditDistanceSearchModifier(int edThresh) {
        this.edThresh = edThresh;
    }

    public int getEdThresh() {
        return edThresh;
    }

    public void setEdThresh(int edThresh) {
        this.edThresh = edThresh;
    }

    @Override
    public int getOccurrenceThreshold(int numQueryTokens) {
        return numQueryTokens - edThresh;
    }

    @Override
    public int getNumPrefixLists(int occurrenceThreshold, int numInvLists) {
        return numInvLists - occurrenceThreshold + 1;
    }

    @Override
    public short getNumTokensLowerBound(short numQueryTokens) {
        return (short) (numQueryTokens - edThresh);
    }

    @Override
    public short getNumTokensUpperBound(short numQueryTokens) {
        return (short) (numQueryTokens + edThresh);
    }
    
    @Override
    public String toString() {
        return "List Edit Distance Search Modifier, Threshold: " + edThresh;
    }
}
