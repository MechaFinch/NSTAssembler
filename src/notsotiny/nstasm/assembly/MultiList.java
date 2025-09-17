package notsotiny.nstasm.assembly;

import java.util.ArrayList;
import java.util.List;

/**
 * A list whose elements may be lists
 * @param <T>
 */
public class MultiList<T> {
    
    private T singleElement;
    
    private List<MultiList<T>> multipleElements;
    
    private MultiList<T> defaultElement;
    
    /**
     * Creates a multilist with the given default element and capacity
     * @param defaultElement
     */
    public MultiList(int capacity, T defaultElement) {
        this.singleElement = null;
        this.multipleElements = new ArrayList<>(capacity);
        this.defaultElement = new MultiList<>(defaultElement);
    }
    
    /**
     * Create a multilist with a single element
     */
    private MultiList(T singleElement) {
        this.singleElement = singleElement;
        this.defaultElement = this;
        this.multipleElements = null;
    }
    
    /**
     * Add a single element
     * @param element
     */
    public void add(T element) {
        this.multipleElements.add(new MultiList<>(element));
    }
    
    /**
     * Add a list as an element
     * @param element
     */
    public void add(MultiList<T> element) {
        this.multipleElements.add(element);
    }
    
    /**
     * Get size
     * @return
     */
    public int size() {
        if(this.singleElement != null) {
            return 1;
        } else {
            return this.multipleElements.size();
        }
    }
    
    /**
     * Get by index
     * @param index
     * @return
     */
    private MultiList<T> get(int index) {
        if(this.singleElement != null) {
            return this;
        } else if(index >= this.multipleElements.size()) {
            return this.defaultElement;
        } else {
            return this.multipleElements.get(index);
        }
    }
    
    /**
     * Get according to multi index
     * @param indices
     * @return
     */
    public MultiList<T> getMultiple(List<Integer> indices) {
        MultiList<T> l = this;
        
        for(int i : indices) {
            l = l.get(i);
        }
        
        return l;
    }
    
    /**
     * Get according to multi index
     * @param indices
     * @return
     */
    public T getSingle(List<Integer> indices) {
        MultiList<T> l = this;
        
        for(int i : indices) {
            l = l.get(i);
        }
        
        return l.singleElement;
    }
    
    /**
     * Gets the first single element
     * @return
     */
    public T getFirst() {
        if(this.singleElement != null) {
            return this.singleElement;
        } else {
            return this.multipleElements.getFirst().getFirst();
        }
    }
    
    /**
     * Gets the first single element of the list with the given index
     * @param indices
     * @return
     */
    public T getFirst(List<Integer> indices) {
        return getMultiple(indices).getFirst();
    }
    
    /**
     * Get the last single element
     * @return
     */
    public T getLast() {
        if(this.singleElement != null) {
            return this.singleElement;
        } else {
            return this.multipleElements.getLast().getLast();
        }
    }
    
    /**
     * Gets the last single element of the list with the given index
     * @param indices
     * @return
     */
    public T getLast(List<Integer> indices) {
        return getMultiple(indices).getLast();
    }
    
}
