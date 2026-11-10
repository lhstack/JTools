// Missing entries follow policy. Editing visible rows never materializes untouched inherited values.
export function effectiveSelection(keys, overrides, includeNew) {
  return keys.filter((key) => overrides[key] ?? includeNew);
}
export function updateSelection(keys, overrides, includeNew, selected) {
  const next = { ...overrides };
  const selectedSet = new Set(selected);
  for (const key of keys) {
    if ((overrides[key] ?? includeNew) !== selectedSet.has(key))
      next[key] = selectedSet.has(key);
  }
  return next;
}
