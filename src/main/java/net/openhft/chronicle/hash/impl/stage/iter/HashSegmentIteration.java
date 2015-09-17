/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.hash.impl.stage.iter;

import net.openhft.chronicle.hash.HashEntry;
import net.openhft.chronicle.hash.HashSegmentContext;
import net.openhft.chronicle.hash.impl.CompactOffHeapLinearHashTable;
import net.openhft.chronicle.hash.impl.VanillaChronicleHashHolder;
import net.openhft.chronicle.hash.impl.stage.entry.HashEntryStages;
import net.openhft.chronicle.hash.impl.stage.entry.HashLookupPos;
import net.openhft.chronicle.hash.impl.stage.entry.SegmentStages;
import net.openhft.chronicle.hash.impl.stage.hash.CheckOnEachPublicOperation;
import net.openhft.sg.StageRef;
import net.openhft.sg.Staged;

import java.util.function.Consumer;
import java.util.function.Predicate;

@Staged
public abstract class HashSegmentIteration<K, E extends HashEntry<K>>
        implements HashEntry<K>, HashSegmentContext<K, E> {
    
    @StageRef public IterationSegmentStages s;
    @StageRef HashEntryStages<K> e;
    @StageRef VanillaChronicleHashHolder<?, ?, ?> hh;
    @StageRef public CheckOnEachPublicOperation checkOnEachPublicOperation;
    @StageRef protected HashLookupPos hlp;
    
    public boolean entryIsPresent() {
        return true;
    }

    public boolean entryRemovedOnThisIteration = false;
    
    abstract boolean entryRemovedOnThisIterationInit();
    
    protected void initEntryRemovedOnThisIteration(boolean entryRemovedOnThisIteration) {
        this.entryRemovedOnThisIteration = entryRemovedOnThisIteration;
    }

    public long hashLookupEntry = 0;

    public void initHashLookupEntry(long entry) {
        hashLookupEntry = entry;
    }

    abstract void closeHashLookupEntry();

    private void goToLastTier() {
        while (s.hasNextTier()) {
            s.nextTier();
        }
    }

    @Override
    public boolean forEachSegmentEntryWhile(Predicate<? super E> action) {
        s.innerUpdateLock.lock();
        try {
            long size = s.size();
            if (size == 0)
                return true;
            goToLastTier();
            while (true) {
                int currentTier = s.segmentTier;
                long currentTierBaseAddr = s.segmentBaseAddr;
                size = forEachTierWhile(action, size, currentTier, currentTierBaseAddr);
                if (size < 0) // interrupted
                    return false;
                if (size == 0)
                    return true;
                if (currentTier == 0)
                    throw new IllegalStateException("We iterated all tiers without interruption, " +
                            "but according to size counter there should be " + size + " more " +
                            "entries. Size diverged?");
                s.prevTier();
            }
        } finally {
            closeHashLookupEntry();
            s.innerReadLock.unlock();
            initEntryRemovedOnThisIteration(false);
        }
    }

    private long forEachTierWhile(
            Predicate<? super E> action, long size, int currentTier, long currentTierBaseAddr) {
        boolean interrupted = false;
        long startPos = 0L;
        CompactOffHeapLinearHashTable hashLookup = hh.h().hashLookup;
        while (!hashLookup.empty(hashLookup.readEntry(s.segmentBaseAddr, startPos))) {
            startPos = hashLookup.step(startPos);
        }
        hlp.initHashLookupPos(startPos);
        do {
            hlp.setHashLookupPos(hashLookup.step(hlp.hashLookupPos));
            long entry = hashLookup.readEntry(s.segmentBaseAddr, hlp.hashLookupPos);
            initHashLookupEntry(entry);
            if (!hashLookup.empty(entry)) {
                e.readExistingEntry(hashLookup.value(entry));
                if (entryIsPresent()) {
                    initEntryRemovedOnThisIteration(false);
                    try {
                        if (!action.test((E) this)) {
                            interrupted = true;
                            break;
                        } else {
                            if (--size == 0)
                                break;
                        }
                    } finally {
                        // if doReplaceValue() -> relocation() -> alloc() -> nextTier()
                        // was called, restore the tier we were iterating over
                        if (s.segmentTier != currentTier)
                            s.initSegmentTier_WithBaseAddr(currentTier, currentTierBaseAddr);
                    }
                }
            }
        } while (hlp.hashLookupPos != startPos);
        return interrupted ? ~size : size;
    }

    @Override
    public void forEachSegmentEntry(Consumer<? super E> action) {
        forEachSegmentEntryWhile(e -> {
            action.accept(e);
            return true;
        });
    }
    
    public void checkEntryNotRemovedOnThisIteration() {
        if (entryRemovedOnThisIterationInit())
            throw new IllegalStateException("Entry was already removed on this iteration");
    }

    @Override
    public void doRemove() {
        checkOnEachPublicOperation.checkOnEachPublicOperation();
        s.innerWriteLock.lock();
        try {
            iterationRemove();
        } finally {
            s.innerWriteLock.unlock();
        }
        initEntryRemovedOnThisIteration(true);
    }

    public void iterationRemove() {
        // this condition mean -- some other entry taken place of the removed one
        if (hh.h().hashLookup.remove(s.segmentBaseAddr, hlp.hashLookupPos) != hlp.hashLookupPos) {
            // if so, should make step back, to compensate step forward on the next iteration,
            // to consume the shifted entry
            hlp.setHashLookupPos(hh.h().hashLookup.stepBack(hlp.hashLookupPos));
        }
        e.innerRemoveEntryExceptHashLookupUpdate();
    }
}
