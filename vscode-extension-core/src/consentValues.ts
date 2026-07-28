export const REGIONS = [
  'africa',
  'americas',
  'apac',
  'china',
  'europe',
  'middle_east',
  'oceania',
] as const;
export type Region = (typeof REGIONS)[number];

export const DATA_SHARING_CHOICES = ['full', 'anonymous', 'none'] as const;
export type DataSharingChoice = (typeof DATA_SHARING_CHOICES)[number];

export const UNSET_CONSENT_VALUE = 'not_set';
export const REGION_SETTING_VALUES = [...REGIONS, UNSET_CONSENT_VALUE] as const;
export const DATA_SHARING_SETTING_VALUES = [...DATA_SHARING_CHOICES, UNSET_CONSENT_VALUE] as const;

export const REGION_LABELS = {
  africa: 'Africa',
  americas: 'Americas',
  apac: 'Asia (Except Mainland China)',
  china: 'Mainland China',
  europe: 'Europe',
  middle_east: 'Middle East',
  oceania: 'Oceania',
} as const satisfies Record<Region, string>;

export const DATA_SHARING_LABELS = {
  full: 'Send Detailed Statistics',
  anonymous: 'Send Anonymous Statistics',
  none: "Don't Send",
} as const satisfies Record<DataSharingChoice, string>;

export const DATA_SHARING_DESCRIPTIONS = {
  full: 'Send anonymous usage statistics and full error reports (including exception messages and attachments).',
  anonymous:
    'Send anonymous usage statistics and anonymized error reports (messages and user data stripped).',
  none: 'Do not send usage statistics or error reports.',
} as const satisfies Record<DataSharingChoice, string>;

export function isDataSharingChoice(value: unknown): value is DataSharingChoice {
  return DATA_SHARING_CHOICES.some((choice) => choice === value);
}

export function isRegion(value: unknown): value is Region {
  return REGIONS.some((region) => region === value);
}
