package se.kth.swim;

import java.util.Iterator;

public class MembershipList<C> {
	private FifoQueue<C> queue;
	
	public MembershipList(int size) {
		this.queue = new FifoQueue<C>(size);
	}

	public FifoQueue<C> getQueue() {
		return queue;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		
		sb.append("MembershipList: ").append("\n");
		Iterator<C> iterator = queue.getIterator();
		
		while (iterator.hasNext()) {
			sb.append("Item: ").append(iterator.next().toString()).append("\n");
		}
		
		return sb.toString();
	}
}
