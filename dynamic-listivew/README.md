`DynamicListView.java` is an improvement over the original `DynamicListView` posted by [DevBytes](https://www.youtube.com/watch?v=_BZIvjMgH-Q&noredirect=1). A lot of the changes are based on the changes made in the [ListViewAnimations library](https://github.com/nhaarman/ListViewAnimations). [this class](https://github.com/nhaarman/ListViewAnimations/blob/master/lib-manipulation/src/main/java/com/nhaarman/listviewanimations/itemmanipulation/DynamicListView.java) among others.

We tried to keep most of what we added towards the bottom of the class, so it starts on [line 665](https://github.com/fulcrumapp/samples-android/blob/master/dynamic-listivew/DynamicListView.java#L665), and the comments help to explain why that code was added. 

- We had to have one `ScrollListener` that propagates calls to all the other `ScrollListener`s because the scroll listener for the `FloatingActionButton` will overwrite the `ListView`'s continuous auto scroll listener (and therefore scrolling at the top and bottom of the list wont be smooth).

- We had to add a few things to allow the rows to be dragged by an icon instead of long press -- this lets the long press action be available for a different type of action (contextual action bar).

### Requirements

To use this class, you must use an `ArrayAdapter` (reference [line 436](https://github.com/fulcrumapp/samples-android/blob/master/dynamic-listivew/DynamicListView.java#L436)), and also override the `getItemId(int position)` method; i.e. something like:

    @Override
    public long getItemId(int position) {
        if ( position < 0 || position >= getCount() ) {
            return -1;
        }
        else {
            MapLayer ml = getItem(position);
            return ml == null ? -1 : ml.getRowID();
        }
    }
