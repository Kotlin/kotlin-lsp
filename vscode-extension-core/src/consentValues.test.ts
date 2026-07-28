import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isDataSharingChoice, isRegion, REGION_LABELS, REGIONS } from './consentValues';

// 'not_set' is the sentinel stored in settings until the user chooses; it must never pass a guard.
const invalidValues = ['not_set', '', undefined, 'unknown'];

test('isRegion rejects unset and unknown values', () => {
  for (const value of invalidValues) {
    assert.equal(isRegion(value), false);
  }
});

test('isDataSharingChoice rejects unset and unknown values', () => {
  for (const value of invalidValues) {
    assert.equal(isDataSharingChoice(value), false);
  }
});

test('every region label is non-empty', () => {
  for (const region of REGIONS) {
    assert.notEqual(REGION_LABELS[region], '');
  }
});
