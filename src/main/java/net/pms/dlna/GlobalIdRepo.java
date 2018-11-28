package net.pms.dlna;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalIdRepo {
	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalIdRepo.class);

	// Global ids start at 1, since id 0 is reserved as a pseudonym for 'renderer root'
	private int curGlobalId = 1, deletionsCount = 0;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final ArrayList<ID> ids = new ArrayList<>();

	private class ID {
		int id;
		boolean isValid;
		weakDLNARef dlnaRef;

		private ID(DLNAResource dlnaResource, int id) {
			this.id = id;
			setRef(dlnaResource);
			isValid = true;
		}

		void setRef(DLNAResource dlnaResource) {
			if (dlnaRef != null) {
				dlnaRef.cancel();
			}
			dlnaRef = new weakDLNARef(dlnaResource, id);
			dlnaResource.setIndexId(id);
		}
	}

	public GlobalIdRepo() {
		startIdCleanup();
	}

	public void add(DLNAResource dlnaResource) {
		lock.writeLock().lock();
		try {
			String id = dlnaResource.getId();
			if (dlnaResource.getId() == null || get(dlnaResource.getId()) != dlnaResource) {
				ids.add(new ID(dlnaResource, curGlobalId++));
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void delete(int index) {
		lock.readLock().lock();
		try {
			if (index > -1 && index < ids.size()) {
				ids.remove(index);
				deletionsCount++;
			}
		} finally {
			lock.readLock().unlock();
		}
	}

	public DLNAResource get(String id) {
		return id != null ? get(parseIndex(id)) : null;
	}

	public DLNAResource get(int id) {
		ID item = getItem(id);
		if (item != null && !item.isValid) {
			LOGGER.debug("Id {} is marked invalid, returning null", id);
			item = null;
		}
		return item != null ? item.dlnaRef.get() : null;
	}

	public ID getItem(int id) {
		lock.readLock().lock();
		try {
			if (id > 0) {
				int index = indexOf(id);
				if (index > -1) {
					return ids.get(index);
				}
			}
			return null;
		} finally {
			lock.readLock().unlock();
		}
	}

	public boolean exists(String id) {
		return get(id) != null;
	}

	public void replace(DLNAResource a, DLNAResource b) {
		ID item = getItem(parseIndex(a.getId()));
		if (item != null) {
			synchronized (lock) {
				lock.readLock().lock();
				try {
					item.setRef(b);
				} finally {
					lock.writeLock().unlock();
				}
			}
		}
	}

	// Here isValid=false means util.DLNAList is telling us the underlying
	// DLNAResource has been removed and we should ignore its id, i.e. not
	// share any hard references to it via get(), between now and whenever
	// garbage collection actually happens

	public void setValid(DLNAResource dlnaresource, boolean isValid) {
		lock.readLock().lock();
		try {
			ID item = getItem(parseIndex(dlnaresource.getId()));
			if (item != null) {
				LOGGER.debug("Marking id {} as {}valid", item.id, isValid ? "" : "in");
				item.isValid = isValid;
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public static int parseIndex(String id) {
		try {
			// Id strings may have optional tags beginning with $ appended, e.g. '1234$Temp'
			return Integer.parseInt(StringUtils.substringBefore(id, "$"));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private int indexOf(int id) {
		lock.readLock().lock();
		try {
			if (id > 0 && id < curGlobalId) {
				// We're in sequence by definition, so binary search is quickest

				// Exclude any areas where the id can't possibly be
				int ceil = ids.size() - 1;
				int top = id - 1; // id 0 is reserved, so index is id-1 at most
				int hi = top < ceil ? top : ceil;
				int floor = hi - deletionsCount;
				int lo = floor > 0 ? floor : 0;

				while (lo <= hi) {
					int mid = lo + (hi - lo) / 2;
					int idm = ids.get(mid).id;
					if (id < idm) {
						hi = mid - 1;
					} else if (id > idm) {
						lo = mid + 1;
					} else {
						return mid;
					}
				}
			}
		} finally {
			lock.readLock().unlock();
		}
		LOGGER.debug("GlobalIdRepo: id not found: {}", id);
		return -1;
	}

	// id cleanup

	ReferenceQueue<DLNAResource> idCleanupQueue;

	class weakDLNARef extends WeakReference<DLNAResource> {
		int id;
		weakDLNARef(DLNAResource dlnaresource, int id) {
			super(dlnaresource, idCleanupQueue);
			this.id = id;
		}
		void cancel() {
			// We've been replaced, i.e. another weakDLNARef is now holding our
			// id, and it will trigger id cleanup at garbage collection time
			id = -1;
		}
	}

	private void startIdCleanup() {
		idCleanupQueue = new ReferenceQueue<>();
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						// Once an underlying DLNAResource is ready for garbage
						// collection, its weak reference will pop out here
						weakDLNARef ref = (weakDLNARef)idCleanupQueue.remove();
						if (ref.id > 0) {
							// Delete the associated id from our repo list
							LOGGER.debug("deleting invalid id {}", ref.id);
							delete(indexOf(ref.id));
						}
					} catch (InterruptedException e) {
					}
				}
			}
		}, "GlobalId cleanup").start();
	}
}
