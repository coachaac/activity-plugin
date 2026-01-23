import { registerPlugin } from '@capacitor/core';

// Import de l'interface
import type { ActivityRecognitionPlugin } from './definitions';

// Enregistrement du plugin auprès de Capacitor
const ActivityRecognition = registerPlugin<ActivityRecognitionPlugin>('ActivityRecognition', {
  web: () => import('./web').then(m => new m.ActivityRecognitionWeb()),
});

// Exportation de tout ce qui est dans definitions.ts (dont ActivityEvent)
export * from './definitions';
export * from './web';
// Exportation de l'instance du plugin
export { ActivityRecognition };