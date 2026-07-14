interface RunWithEulaGateOptions {
  checkEulaAccepted: () => Promise<boolean>;
  action: () => Promise<void>;
}

export async function runWithEulaGate({
  checkEulaAccepted,
  action,
}: RunWithEulaGateOptions): Promise<boolean> {
  const eulaAccepted = await checkEulaAccepted();
  if (!eulaAccepted) return false;

  await action();
  return true;
}
