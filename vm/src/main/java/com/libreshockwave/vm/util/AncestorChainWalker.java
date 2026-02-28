package com.libreshockwave.vm.util;

import com.libreshockwave.vm.Datum;

/**
 * Utility class for walking ancestor chains in script instances.
 * Consolidates the duplicated ancestor chain traversal logic from
 * CallOpcodes and PropertyOpcodes.
 */
public final class AncestorChainWalker {

    public static final int MAX_ANCESTOR_DEPTH = 100;

    private AncestorChainWalker() {}

    /**
     * Get a property from a script instance, walking the ancestor chain if not found.
     * @param instance The script instance to start searching from
     * @param propName The property name to look for
     * @return The property value, or VOID if not found
     */
    public static Datum getProperty(Datum.ScriptInstance instance, String propName) {
        Datum.ScriptInstance current = instance;
        for (int i = 0; i < MAX_ANCESTOR_DEPTH; i++) {
            if (current.properties().containsKey(propName)) {
                return current.properties().get(propName);
            }

            // Try ancestor
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }
        return Datum.VOID;
    }

    /**
     * Check if a property exists in the script instance or its ancestor chain.
     * @param instance The script instance to start searching from
     * @param propName The property name to look for
     * @return true if the property exists somewhere in the chain
     */
    public static boolean hasProperty(Datum.ScriptInstance instance, String propName) {
        Datum.ScriptInstance current = instance;
        for (int i = 0; i < MAX_ANCESTOR_DEPTH; i++) {
            if (current.properties().containsKey(propName)) {
                return true;
            }

            // Try ancestor
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }
        return false;
    }

    /**
     * Find the script instance in the ancestor chain that owns a property.
     * @param instance The script instance to start searching from
     * @param propName The property name to look for
     * @return The instance that owns the property, or null if not found
     */
    public static Datum.ScriptInstance findOwner(Datum.ScriptInstance instance, String propName) {
        Datum.ScriptInstance current = instance;
        for (int i = 0; i < MAX_ANCESTOR_DEPTH; i++) {
            if (current.properties().containsKey(propName)) {
                return current;
            }

            // Try ancestor
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }
        return null;
    }

    /**
     * Get the ancestor at a specific depth.
     * @param instance The script instance to start from
     * @param depth The depth to traverse (1 = immediate ancestor)
     * @return The ancestor at the specified depth, or null if not found
     */
    public static Datum.ScriptInstance getAncestorAtDepth(Datum.ScriptInstance instance, int depth) {
        if (depth < 1) {
            return null;
        }

        Datum.ScriptInstance current = instance;
        for (int i = 0; i < depth && i < MAX_ANCESTOR_DEPTH; i++) {
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Set a property on a script instance, walking the ancestor chain to find the owner.
     * If the property exists on the current instance or an ancestor, sets it there.
     * If not found anywhere, adds to the current instance.
     * @param instance The script instance to start searching from
     * @param propName The property name to set
     * @param value The value to set
     */
    public static void setProperty(Datum.ScriptInstance instance, String propName, Datum value) {
        Datum.ScriptInstance owner = findOwner(instance, propName);
        if (owner != null) {
            owner.properties().put(propName, value);
        } else {
            // Property not declared anywhere - add to current instance
            instance.properties().put(propName, value);
        }
    }

    /**
     * Count the depth of the ancestor chain.
     * @param instance The script instance to start from
     * @return The number of ancestors (0 if no ancestors)
     */
    public static int getAncestorDepth(Datum.ScriptInstance instance) {
        int depth = 0;
        Datum.ScriptInstance current = instance;

        for (int i = 0; i < MAX_ANCESTOR_DEPTH; i++) {
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                depth++;
                current = ancestorInstance;
            } else {
                break;
            }
        }
        return depth;
    }
}
