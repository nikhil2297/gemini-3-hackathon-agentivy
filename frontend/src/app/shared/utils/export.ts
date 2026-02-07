export function downloadJson(data: unknown, filenamePrefix: string): void {
  const dataStr = JSON.stringify(data, null, 2);
  const dataUri = 'data:application/json;charset=utf-8,' + encodeURIComponent(dataStr);
  const filename = `${filenamePrefix}-${Date.now()}.json`;

  const link = document.createElement('a');
  link.setAttribute('href', dataUri);
  link.setAttribute('download', filename);
  link.click();
}
