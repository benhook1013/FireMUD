import TextField from '@mui/material/TextField';
import Box from '@mui/material/Box';

export default function GameEditor() {
  return (
    <Box sx={{ p: 2 }}>
      <h2>Game Editor</h2>
      <TextField label="Room Name" placeholder="e.g., Dark Forest" fullWidth />
    </Box>
  );
}
